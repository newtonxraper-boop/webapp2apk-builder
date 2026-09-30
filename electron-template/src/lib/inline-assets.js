'use strict';

/**
 * Makes a cached page look right offline: finds the CSS, JS and images an
 * HTML page references and inlines them, so the page doesn't depend on any
 * network request once it's shown from the cache.
 *
 * This is what was missing before - the HTML text was cached, but its
 * stylesheet/script/image requests still hit the network and failed offline,
 * so a cached page rendered unstyled.
 *
 * Pure string/regex based (no DOM parser dependency, so it stays testable
 * without Electron or jsdom). It intentionally only handles the common,
 * well-formed cases - <link rel=stylesheet>, <script src>, <img src>,
 * CSS url(...) references (in both inline <style> blocks and stylesheets),
 * and srcset. Anything it can't confidently rewrite is left as-is, which
 * just means that one resource stays a live (and offline, failing) request -
 * never a broken page.
 */

const MAX_ASSET_BYTES = 900 * 1024;
const MAX_INLINE_ASSETS = 60;

function resolve(url, base) {
  try {
    return new URL(url, base).toString();
  } catch (e) {
    return null;
  }
}

function isDataOrSpecial(url) {
  return /^(data:|mailto:|tel:|javascript:|#)/i.test(url.trim());
}

function toDataUri(mime, body) {
  return 'data:' + (mime || 'application/octet-stream') + ';base64,' + body.toString('base64');
}

/**
 * Finds every same-origin asset URL an HTML page references (stylesheets,
 * scripts, images, and url(...) references inside inline <style> blocks).
 * @returns {string[]} deduplicated, resolved URLs
 */
function extractAssetUrls(html, baseUrl) {
  const found = new Set();
  const add = (raw) => {
    if (!raw) return;
    const trimmed = raw.trim();
    if (!trimmed || isDataOrSpecial(trimmed)) return;
    const abs = resolve(trimmed, baseUrl);
    if (abs) found.add(abs);
  };

  const linkRe = /<link\b[^>]*>/gi;
  let m;
  while ((m = linkRe.exec(html))) {
    const tag = m[0];
    if (!/rel\s*=\s*["']?[^"'>]*stylesheet/i.test(tag)) continue;
    const hrefM = /href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i.exec(tag);
    if (hrefM) add(hrefM[1] || hrefM[2] || hrefM[3]);
  }

  const scriptRe = /<script\b[^>]*\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))[^>]*>/gi;
  while ((m = scriptRe.exec(html))) add(m[1] || m[2] || m[3]);

  const imgRe = /<img\b[^>]*>/gi;
  while ((m = imgRe.exec(html))) {
    const tag = m[0];
    const srcM = /\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i.exec(tag);
    if (srcM) add(srcM[1] || srcM[2] || srcM[3]);
    const srcsetM = /\bsrcset\s*=\s*(?:"([^"]*)"|'([^']*)')/i.exec(tag);
    if (srcsetM) {
      const val = srcsetM[1] || srcsetM[2] || '';
      val.split(',').forEach((part) => add(part.trim().split(/\s+/)[0]));
    }
  }

  const styleBlockRe = /<style\b[^>]*>([\s\S]*?)<\/style>/gi;
  while ((m = styleBlockRe.exec(html))) extractCssUrls(m[1], baseUrl).forEach((u) => found.add(u));

  return Array.from(found).slice(0, MAX_INLINE_ASSETS);
}

function extractCssUrls(css, baseUrl) {
  const found = [];
  const urlRe = /url\(\s*(?:"([^"]*)"|'([^']*)'|([^)'"]*))\s*\)/gi;
  let m;
  while ((m = urlRe.exec(css))) {
    const raw = (m[1] || m[2] || m[3] || '').trim();
    if (!raw || isDataOrSpecial(raw)) continue;
    const abs = resolve(raw, baseUrl);
    if (abs) found.push(abs);
  }
  return found;
}

/** Rewrites url(...) references inside a CSS string to data: URIs using `lookup`. */
function inlineCssUrls(css, baseUrl, lookup) {
  return css.replace(/url\(\s*(?:"([^"]*)"|'([^']*)'|([^)'"]*))\s*\)/gi, (whole, a, b, c) => {
    const raw = (a || b || c || '').trim();
    if (!raw || isDataOrSpecial(raw)) return whole;
    const abs = resolve(raw, baseUrl);
    if (!abs) return whole;
    const asset = lookup(abs);
    if (!asset) return whole;
    return 'url("' + toDataUri(asset.mime, asset.body) + '")';
  });
}

/**
 * Rewrites an HTML page's stylesheet/script/image references to inline
 * data: content, using whatever `lookup(url) -> {mime, body:Buffer} | null`
 * already has cached. References that aren't cached are left untouched -
 * they'll simply fail as a normal offline network request would, they just
 * won't crash anything.
 * @returns {string} the rewritten HTML
 */
function inlineAssets(html, baseUrl, lookup) {
  let out = html;

  out = out.replace(/<link\b[^>]*>/gi, (tag) => {
    if (!/rel\s*=\s*["']?[^"'>]*stylesheet/i.test(tag)) return tag;
    const hrefM = /href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i.exec(tag);
    if (!hrefM) return tag;
    const abs = resolve((hrefM[1] || hrefM[2] || hrefM[3]).trim(), baseUrl);
    if (!abs) return tag;
    const asset = lookup(abs);
    if (!asset) return tag;
    const css = inlineCssUrls(asset.body.toString('utf8'), abs, lookup);
    return '<style>' + css + '</style>';
  });

  out = out.replace(/<script\b([^>]*)\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))([^>]*)>(\s*)<\/script>/gi,
    (whole, pre, a, b, c, post, inner) => {
      // Only collapse genuinely empty script tags (a script with both a src
      // and inline body is invalid HTML anyway, but never risk dropping content).
      if (inner && inner.trim()) return whole;
      const raw = (a || b || c || '').trim();
      const abs = resolve(raw, baseUrl);
      if (!abs) return whole;
      const asset = lookup(abs);
      if (!asset) return whole;
      const attrs = (pre + post).replace(/\btype\s*=\s*(?:"module"|'module')/i, '').replace(/\s+/g, ' ').trim();
      return '<script' + (attrs ? ' ' + attrs : '') + '>' + asset.body.toString('utf8').replace(/<\/script/gi, '<\\/script') + '</script>';
    });

  out = out.replace(/<img\b[^>]*>/gi, (tag) => {
    const srcM = /\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i.exec(tag);
    if (!srcM) return tag;
    const raw = (srcM[1] || srcM[2] || srcM[3] || '').trim();
    if (!raw || isDataOrSpecial(raw)) return tag;
    const abs = resolve(raw, baseUrl);
    if (!abs) return tag;
    const asset = lookup(abs);
    if (!asset) return tag;
    const dataUri = toDataUri(asset.mime, asset.body);
    let rewritten = tag.replace(srcM[0], 'src="' + dataUri + '"');
    // A cached, inlined image should win over srcset (which we don't rewrite).
    rewritten = rewritten.replace(/\s+srcset\s*=\s*(?:"[^"]*"|'[^']*')/i, '');
    return rewritten;
  });

  out = out.replace(/<style\b([^>]*)>([\s\S]*?)<\/style>/gi,
    (whole, attrs, css) => '<style' + attrs + '>' + inlineCssUrls(css, baseUrl, lookup) + '</style>');

  return out;
}

module.exports = { extractAssetUrls, extractCssUrls, inlineAssets, inlineCssUrls, toDataUri, MAX_ASSET_BYTES };
