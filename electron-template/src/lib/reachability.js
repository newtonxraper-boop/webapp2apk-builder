'use strict';

/**
 * Direct reachability probe against the web app's OWN domain - the desktop
 * counterpart of NetworkReachability.java. "The OS says we're online" isn't
 * ground truth: a captive portal (hotel / airport Wi-Fi login, an operator's
 * "top up now" page) reports a working network while every request is really
 * being redirected to the portal's own domain. So this does a HEAD (falling
 * back to GET, since some servers answer 405 to HEAD) and treats a redirect
 * to a DIFFERENT host as "not reachable". Redirects to the same host
 * (http -> https, / -> /login) are followed a few hops. Any other response,
 * including 4xx/5xx from the app itself, counts as reachable: the network
 * path to the domain works, which is all this checks.
 *
 * The HTTP call is injected (`requester`) so this has no Electron dependency.
 *   requester(url, method, timeoutMs) ->
 *     Promise<{status:number, location?:string}>   (rejects on connection failure;
 *     must NOT follow redirects itself)
 */

const MAX_SAME_HOST_REDIRECTS = 3;
const DEFAULT_TIMEOUT_MS = 4000;

async function probeOnce(requester, urlString, method, allowMethodFallback, redirectsLeft, timeoutMs) {
  const res = await requester(urlString, method, timeoutMs);
  const status = res && res.status;

  if (status === 405 || status === 501) {
    if (allowMethodFallback) return probeOnce(requester, urlString, 'GET', false, redirectsLeft, timeoutMs);
    return false;
  }

  if (status >= 300 && status < 400) {
    if (redirectsLeft <= 0) return false;
    if (!res.location) return false;
    let target;
    try {
      target = new URL(res.location, urlString);
    } catch (e) {
      return false;
    }
    const originalHost = new URL(urlString).hostname.toLowerCase();
    if (target.hostname.toLowerCase() !== originalHost) {
      // Redirected off to a different domain entirely - the captive-portal /
      // DNS-hijack signature.
      return false;
    }
    return probeOnce(requester, target.toString(), method, allowMethodFallback, redirectsLeft - 1, timeoutMs);
  }

  return status > 0;
}

async function probe(requester, urlString, opts) {
  const timeoutMs = (opts && opts.timeoutMs) || DEFAULT_TIMEOUT_MS;
  try {
    return await probeOnce(requester, urlString, 'HEAD', true, MAX_SAME_HOST_REDIRECTS, timeoutMs);
  } catch (e) {
    return false;
  }
}

module.exports = { probe, MAX_SAME_HOST_REDIRECTS };
