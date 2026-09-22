'use strict';

/**
 * The script injected into the app's own pages (main world, at document start,
 * from guest-preload.js). It is the desktop counterpart of MainActivity's
 * injectOfflineQueueScript() + injectPrintOverrideScript() + the location
 * bridge, and does five things:
 *
 *   1. While offline, catches <form> submits, fetch() and XMLHttpRequest
 *      writes (POST/PUT/PATCH/DELETE), hands them to the offline queue
 *      through the `bridge`, and makes the page think they succeeded (the
 *      offline banner is the only cue) - exactly like the APK.
 *   2. While online, shrinks big photo uploads in forms before they're sent.
 *   3. Redirects window.print() to the native print dialog.
 *   4. Provides window.AndroidLocation (getCurrentPosition / startGeofence /
 *      stopGeofence + the same window.onLocationResult / onLocationError /
 *      onGeofenceEvent callbacks) on top of navigator.geolocation.
 *
 * IMPORTANT: installPageHooks is serialised with Function.prototype.toString()
 * and evaluated inside the page, so it must stay completely self-contained -
 * no references to anything outside its own body. `window`, `document` and
 * `bridge` are parameters (not globals) purely so it can also be unit-tested
 * against a fake DOM.
 *
 * bridge (exposed by guest-preload.js through contextBridge):
 *   enqueue(payloadJson) -> Promise<{ok:boolean}>
 *   isOnline()           -> boolean
 *   notifyQueued()       -> void
 *   notifyQueueFailed()  -> void
 *   print()              -> void
 */
function installPageHooks(window, document, bridge, opts) {
  if (window.__w2aQueueInstalled) return;
  window.__w2aQueueInstalled = true;

  var MAX_FILE_BYTES = (opts && opts.maxFileBytes) || 4 * 1024 * 1024;
  var ONLINE_COMPRESS_THRESHOLD_BYTES = 600 * 1024;
  var ONLINE_TARGET_BYTES = 900 * 1024;
  var HEADER_WHITELIST = ['content-type', 'accept', 'x-requested-with', 'x-csrf-token', 'x-xsrf-token', 'csrf-token'];

  // A page shown from the offline cache is loaded as a data: URL with the real
  // address as its base, so relative URLs must be resolved against that base.
  function pageBase() {
    var href = window.location.href;
    if (href.indexOf('data:') === 0 || href === 'about:blank') return (opts && opts.baseUrl) || href;
    return href;
  }

  function fileToBase64(blob) {
    return new Promise(function (resolve, reject) {
      var reader = new window.FileReader();
      reader.onload = function () { resolve(String(reader.result).split(',')[1] || ''); };
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }

  function compressImageOnce(file, maxDim, quality) {
    return new Promise(function (resolve) {
      var url;
      try { url = window.URL.createObjectURL(file); } catch (e) { resolve(file); return; }
      var img = new window.Image();
      img.onload = function () {
        try {
          var w = img.naturalWidth || img.width;
          var h = img.naturalHeight || img.height;
          var scale = Math.min(1, maxDim / Math.max(w, h));
          var cw = Math.max(1, Math.round(w * scale));
          var ch = Math.max(1, Math.round(h * scale));
          var canvas = document.createElement('canvas');
          canvas.width = cw;
          canvas.height = ch;
          canvas.getContext('2d').drawImage(img, 0, 0, cw, ch);
          window.URL.revokeObjectURL(url);
          canvas.toBlob(function (blob) { resolve(blob || file); }, 'image/jpeg', quality);
        } catch (e) {
          window.URL.revokeObjectURL(url);
          resolve(file);
        }
      };
      img.onerror = function () { window.URL.revokeObjectURL(url); resolve(file); };
      img.src = url;
    });
  }

  function compressImageUntilFits(file, maxBytes) {
    var attempts = [[1600, 0.82], [1280, 0.72], [1024, 0.62], [800, 0.55]];
    var i = 0;
    var best = file;
    function step() {
      if (best.size <= maxBytes || i >= attempts.length) return Promise.resolve(best);
      var opt = attempts[i++];
      return compressImageOnce(file, opt[0], opt[1]).then(function (blob) {
        if (blob && blob.size < best.size) best = blob;
        return step();
      });
    }
    return step();
  }

  function serializeFormData(formData) {
    var entries = [];
    formData.forEach(function (value, key) { entries.push([key, value]); });
    return Promise.all(entries.map(function (pair) {
      var key = pair[0];
      var value = pair[1];
      if (typeof window.File !== 'undefined' && value instanceof window.File) {
        if (value.size === 0) return null;
        var isImage = value.type && value.type.indexOf('image/') === 0;
        var prep = (isImage && value.size > MAX_FILE_BYTES)
          ? compressImageUntilFits(value, MAX_FILE_BYTES)
          : Promise.resolve(value);
        return prep.then(function (finalBlob) {
          if (!finalBlob || finalBlob.size > MAX_FILE_BYTES) {
            return { key: key, type: 'file_too_large', name: value.name };
          }
          return fileToBase64(finalBlob).then(function (b64) {
            return { key: key, type: 'file', name: value.name, mime: (finalBlob.type || value.type), data: b64 };
          });
        });
      }
      return { key: key, type: 'text', value: String(value) };
    })).then(function (list) { return list.filter(function (x) { return x; }); });
  }

  function pickHeaders(headersLike) {
    var out = {};
    if (!headersLike) return out;
    try {
      if (typeof headersLike.forEach === 'function' && !Array.isArray(headersLike)) {
        headersLike.forEach(function (v, k) {
          var key = String(k).toLowerCase();
          if (HEADER_WHITELIST.indexOf(key) !== -1) out[key] = String(v);
        });
      } else if (Array.isArray(headersLike)) {
        headersLike.forEach(function (pair) {
          var key = String(pair[0]).toLowerCase();
          if (HEADER_WHITELIST.indexOf(key) !== -1) out[key] = String(pair[1]);
        });
      } else if (typeof headersLike === 'object') {
        Object.keys(headersLike).forEach(function (k) {
          var key = k.toLowerCase();
          if (HEADER_WHITELIST.indexOf(key) !== -1) out[key] = String(headersLike[k]);
        });
      }
    } catch (e) { /* headers are best-effort */ }
    return out;
  }

  // Guards against exactly the kind of bug that produces a permanently stuck
  // sync item: page code accidentally passing a stringified object ("{}",
  // "[object Object]") instead of a real URL. "{}" is a perfectly legal
  // relative path once resolved against the page, so it has to be rejected
  // on the raw value, before it ever reaches the queue.
  function queueSubmission(url, method, fields, enctype, headers) {
    if (typeof url !== 'string' || !url || url.charAt(0) === '{' || url.charAt(0) === '[') {
      if (window.console) window.console.warn('offline queue: refusing to queue invalid url', url);
      return Promise.resolve(false);
    }
    var resolved;
    try { resolved = new window.URL(url, pageBase()).href; } catch (e) { resolved = null; }
    if (!resolved) return Promise.resolve(false);
    var payload = JSON.stringify({
      url: resolved, method: method, enctype: enctype, fields: fields, headers: headers || {}, ts: Date.now(),
    });
    if (!bridge || typeof bridge.enqueue !== 'function') return Promise.resolve(false);
    return Promise.resolve(bridge.enqueue(payload)).then(function (r) { return !!(r && r.ok); }, function () { return false; });
  }

  function reportQueued(queued) {
    try {
      if (queued && bridge.notifyQueued) bridge.notifyQueued();
      else if (!queued && bridge.notifyQueueFailed) bridge.notifyQueueFailed();
    } catch (e) { /* ignore */ }
    return queued;
  }

  function isOffline() {
    try { return !bridge.isOnline(); } catch (e) { return !window.navigator.onLine; }
  }

  // ---- <form> submits -------------------------------------------------
  // Bubble phase on purpose: if the page's own handler already took over the
  // submit (preventDefault, then sends it with fetch/XHR), the fetch/XHR hooks
  // below deal with it and this must stay out of the way.
  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (typeof window.HTMLFormElement === 'undefined' || !(form instanceof window.HTMLFormElement)) return;
    if (e.defaultPrevented) return;
    var method = (form.getAttribute('method') || 'GET').toUpperCase();

    if (isOffline()) {
      // A GET form is a navigation (search, filter...), not a write - it can't
      // be replayed later, so let it fail into the offline/cached page.
      if (method === 'GET' || method === 'DIALOG') return;
      e.preventDefault();
      var formData;
      try { formData = e.submitter ? new window.FormData(form, e.submitter) : new window.FormData(form); }
      catch (err) { formData = new window.FormData(form); }
      var url = form.action || pageBase();
      var enctype = form.enctype || 'application/x-www-form-urlencoded';
      serializeFormData(formData).then(function (fields) {
        return queueSubmission(url, method, fields, enctype, {});
      }).then(reportQueued);
      return;
    }

    // Online: shrink big photos before they go up over a possibly slow link.
    var fileInputs = form.querySelectorAll('input[type=file]');
    var toCompress = [];
    fileInputs.forEach(function (input) {
      if (!input.files || !input.files.length) return;
      for (var i = 0; i < input.files.length; i++) {
        var f = input.files[i];
        if (f.type && f.type.indexOf('image/') === 0 && f.size > ONLINE_COMPRESS_THRESHOLD_BYTES) {
          toCompress.push({ input: input, index: i, file: f });
        }
      }
    });
    if (toCompress.length === 0 || typeof window.DataTransfer === 'undefined') return;
    e.preventDefault();
    var submitter = e.submitter;
    Promise.all(toCompress.map(function (item) {
      return compressImageUntilFits(item.file, ONLINE_TARGET_BYTES).then(function (blob) {
        item.newFile = new window.File([blob], item.file.name, { type: (blob.type || item.file.type) });
      });
    })).then(function () {
      var byInput = new Map();
      toCompress.forEach(function (item) {
        if (!byInput.has(item.input)) byInput.set(item.input, []);
        byInput.get(item.input).push(item);
      });
      byInput.forEach(function (items, input) {
        var repl = {};
        items.forEach(function (it) { repl[it.index] = it.newFile; });
        var dt = new window.DataTransfer();
        for (var i = 0; i < input.files.length; i++) dt.items.add(repl[i] || input.files[i]);
        input.files = dt.files;
      });
      if (submitter && submitter.name) {
        var hidden = document.createElement('input');
        hidden.type = 'hidden';
        hidden.name = submitter.name;
        hidden.value = submitter.value;
        form.appendChild(hidden);
      }
      form.submit();
    }).catch(function () { form.submit(); });
  }, false);

  // ---- fetch() / XMLHttpRequest writes -------------------------------
  function isWriteMethod(m) {
    return m !== 'GET' && m !== 'HEAD' && m !== 'OPTIONS';
  }

  function queueBody(url, method, body, headers) {
    var p;
    if (typeof window.FormData !== 'undefined' && body instanceof window.FormData) {
      p = serializeFormData(body).then(function (fields) {
        return queueSubmission(url, method, fields, 'multipart/form-data', headers);
      });
    } else if (typeof window.URLSearchParams !== 'undefined' && body instanceof window.URLSearchParams) {
      var fields = [];
      body.forEach(function (value, key) { fields.push({ key: key, type: 'text', value: String(value) }); });
      p = queueSubmission(url, method, fields, 'application/x-www-form-urlencoded', headers);
    } else if (typeof body === 'string' && body.length > 0) {
      p = queueSubmission(url, method, [{ key: 'body', type: 'text', value: body }], 'raw', headers);
    } else if (body === undefined || body === null || body === '') {
      // A write with no body (e.g. DELETE /items/5) still has to be replayed.
      p = queueSubmission(url, method, [{ key: 'body', type: 'text', value: '' }], 'raw', headers);
    } else {
      // Blob / ArrayBuffer / stream bodies can't be stored as text.
      p = Promise.resolve(false);
    }
    return p.then(reportQueued);
  }

  var originalFetch = window.fetch;
  if (typeof originalFetch === 'function') {
    window.fetch = function (input, init) {
      init = init || {};
      var isRequest = typeof window.Request !== 'undefined' && input instanceof window.Request;
      var method = String(init.method || (isRequest ? input.method : 'GET')).toUpperCase();
      if (!isWriteMethod(method) || !isOffline()) return originalFetch.apply(this, arguments);

      var url;
      if (typeof input === 'string') url = input;
      else if (input && typeof input.url === 'string') url = input.url;
      else if (input && typeof input.href === 'string') url = input.href;
      else if (input) url = String(input);
      else url = '';

      var headers = pickHeaders(init.headers || (isRequest ? input.headers : null));
      var bodyPromise;
      if (init.body !== undefined) {
        bodyPromise = Promise.resolve(init.body);
      } else if (isRequest) {
        var ct = (input.headers && input.headers.get && input.headers.get('content-type')) || '';
        var clone = input.clone();
        if (/multipart\/form-data|application\/x-www-form-urlencoded/i.test(ct)) {
          bodyPromise = clone.formData().catch(function () { return clone.text(); });
        } else {
          bodyPromise = clone.text();
        }
        bodyPromise = bodyPromise.catch(function () { return undefined; });
      } else {
        bodyPromise = Promise.resolve(undefined);
      }

      return bodyPromise.then(function (body) {
        return queueBody(url, method, body, headers);
      }).then(function () {
        // Resolve as if the request succeeded: the change is safely queued
        // on-device and will really be sent the moment the connection comes
        // back, so the page's normal success handling should run.
        return new window.Response(JSON.stringify({ queued: true, offline: true }), {
          status: 200, statusText: 'OK (queued offline)', headers: { 'Content-Type': 'application/json' },
        });
      });
    };
  }

  var OrigXHR = window.XMLHttpRequest;
  if (OrigXHR && OrigXHR.prototype) {
    var origOpen = OrigXHR.prototype.open;
    var origSend = OrigXHR.prototype.send;
    var origSetHeader = OrigXHR.prototype.setRequestHeader;
    OrigXHR.prototype.open = function (method, url) {
      this.__w2aMethod = String(method || 'GET').toUpperCase();
      if (typeof url === 'string') this.__w2aUrl = url;
      else if (url && typeof url.href === 'string') this.__w2aUrl = url.href;
      else if (url) this.__w2aUrl = String(url);
      else this.__w2aUrl = '';
      this.__w2aHeaders = {};
      return origOpen.apply(this, arguments);
    };
    OrigXHR.prototype.setRequestHeader = function (name, value) {
      try {
        var key = String(name).toLowerCase();
        if (HEADER_WHITELIST.indexOf(key) !== -1) (this.__w2aHeaders = this.__w2aHeaders || {})[key] = String(value);
      } catch (e) { /* ignore */ }
      return origSetHeader.apply(this, arguments);
    };
    OrigXHR.prototype.send = function (body) {
      var self = this;
      var method = this.__w2aMethod || 'GET';
      if (!isWriteMethod(method) || !isOffline()) return origSend.apply(this, arguments);
      queueBody(this.__w2aUrl || '', method, body, this.__w2aHeaders || {}).then(function () {
        setTimeout(function () {
          var fake = { queued: true, offline: true };
          var text = JSON.stringify(fake);
          var value = self.responseType === 'json' ? fake : text;
          function def(prop, v) { try { Object.defineProperty(self, prop, { value: v, configurable: true }); } catch (e) { /* ignore */ } }
          def('readyState', 4);
          def('status', 200);
          def('statusText', 'OK (queued offline)');
          def('response', value);
          if (self.responseType === '' || self.responseType === 'text') def('responseText', text);
          function fire(type) {
            try {
              var handler = self['on' + type];
              if (typeof handler === 'function') handler.call(self, new window.ProgressEvent(type));
              self.dispatchEvent(new window.ProgressEvent(type));
            } catch (e) { /* ignore */ }
          }
          try {
            if (typeof self.onreadystatechange === 'function') self.onreadystatechange();
            self.dispatchEvent(new window.Event('readystatechange'));
          } catch (e) { /* ignore */ }
          fire('load');
          fire('loadend');
        }, 0);
      });
    };
  }

  // ---- window.print() -> native print dialog ----------------------------
  if (bridge && typeof bridge.print === 'function') {
    window.print = function () { bridge.print(); };
  }

  // ---- location + geofencing (AndroidLocation) --------------------------
  var geo = window.navigator && window.navigator.geolocation;
  if (geo) {
    var fences = {};
    var fenceCount = 0;
    var watchId = null;

    var toRad = function (d) { return d * Math.PI / 180; };
    var distanceMeters = function (lat1, lng1, lat2, lng2) {
      var R = 6371000;
      var dLat = toRad(lat2 - lat1);
      var dLng = toRad(lng2 - lng1);
      var a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
      return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    };
    var reasonFor = function (err) {
      if (!err) return 'failed';
      if (err.code === 1) return 'permission_denied';
      if (err.code === 2) return 'unavailable';
      return 'failed';
    };
    var onFenceFix = function (pos) {
      Object.keys(fences).forEach(function (id) {
        var f = fences[id];
        var inside = distanceMeters(pos.coords.latitude, pos.coords.longitude, f.lat, f.lng) <= f.radius;
        if (f.inside === undefined) {
          f.inside = inside;
          if (inside && typeof window.onGeofenceEvent === 'function') window.onGeofenceEvent(id, true);
        } else if (inside !== f.inside) {
          f.inside = inside;
          if (typeof window.onGeofenceEvent === 'function') window.onGeofenceEvent(id, inside);
        }
      });
    };
    var stopWatchIfIdle = function () {
      if (fenceCount === 0 && watchId !== null) { geo.clearWatch(watchId); watchId = null; }
    };

    window.AndroidLocation = {
      getCurrentPosition: function () {
        geo.getCurrentPosition(function (pos) {
          if (typeof window.onLocationResult === 'function') {
            window.onLocationResult(pos.coords.latitude, pos.coords.longitude, pos.coords.accuracy);
          }
        }, function (err) {
          if (typeof window.onLocationError === 'function') window.onLocationError(reasonFor(err));
        }, { enableHighAccuracy: false, timeout: 15000, maximumAge: 60000 });
      },
      startGeofence: function (id, lat, lng, radiusMeters) {
        id = String(id);
        if (!fences[id]) fenceCount++;
        fences[id] = { lat: Number(lat), lng: Number(lng), radius: Number(radiusMeters) || 100, inside: undefined };
        if (watchId === null) {
          watchId = geo.watchPosition(onFenceFix, function (err) {
            if (typeof window.onLocationError === 'function') window.onLocationError(reasonFor(err));
          }, { enableHighAccuracy: false, maximumAge: 30000, timeout: 30000 });
        }
      },
      stopGeofence: function (id) {
        id = String(id);
        if (fences[id]) { delete fences[id]; fenceCount--; }
        stopWatchIfIdle();
      },
    };
  }
}

/** The string evaluated in the page. */
function buildInjection(opts) {
  return '(' + installPageHooks.toString() + ')(window, document, window.__w2a, ' + JSON.stringify(opts || {}) + ');';
}

module.exports = { installPageHooks, buildInjection };
