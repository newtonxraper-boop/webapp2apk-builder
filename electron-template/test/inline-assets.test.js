'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { extractAssetUrls, extractCssUrls, inlineAssets, inlineCssUrls } = require('../src/lib/inline-assets');

const BASE = 'https://shop.example.com/orders';

test('extractAssetUrls finds stylesheet, script, img src/srcset, and resolves relative URLs (host filtering happens later, in AssetCache)', () => {
  const html = `<html><head>
    <link rel="stylesheet" href="/css/app.css">
    <link rel="icon" href="/favicon.ico">
    <script src="scripts/app.js"></script>
    </head><body>
    <img src="/img/logo.png" srcset="/img/logo@2x.png 2x">
    <style>.a { background: url('bg.png'); }</style>
    </body></html>`;
  const urls = extractAssetUrls(html, BASE);
  assert.ok(urls.includes('https://shop.example.com/css/app.css'));
  assert.ok(urls.includes('https://shop.example.com/scripts/app.js'));
  assert.ok(urls.includes('https://shop.example.com/img/logo.png'));
  assert.ok(urls.includes('https://shop.example.com/img/logo@2x.png'));
  assert.ok(urls.includes('https://shop.example.com/bg.png'));
  assert.ok(!urls.includes('https://shop.example.com/favicon.ico'));
});

test('extractAssetUrls ignores data:, javascript:, and empty refs', () => {
  const html = `<img src="data:image/png;base64,AAAA"><script src="javascript:void(0)"></script><img src="">`;
  assert.deepStrictEqual(extractAssetUrls(html, BASE), []);
});

test('extractCssUrls handles quoted, unquoted, and single-quoted url()', () => {
  const css = `.a{background:url("a.png")} .b{background:url('b.png')} .c{background:url(c.png)}`;
  const urls = extractCssUrls(css, BASE);
  assert.strictEqual(urls.length, 3);
  assert.ok(urls.every((u) => u.startsWith('https://shop.example.com/')));
});

function lookupFrom(map) {
  return (url) => map[url] || null;
}

test('inlineAssets replaces a stylesheet link with an inlined <style>, including its own url() refs', () => {
  const html = '<link rel="stylesheet" href="/css/app.css">';
  const css = '.logo{background:url(/img/logo.png)}';
  const lookup = lookupFrom({
    'https://shop.example.com/css/app.css': { mime: 'text/css', body: Buffer.from(css) },
    'https://shop.example.com/img/logo.png': { mime: 'image/png', body: Buffer.from('PNGDATA') },
  });
  const out = inlineAssets(html, BASE, lookup);
  assert.match(out, /^<style>\.logo\{background:url\("data:image\/png;base64,[A-Za-z0-9+/=]+"\)\}<\/style>$/);
});

test('inlineAssets replaces an empty <script src> with its inlined body, escaping </script>', () => {
  const html = '<script src="/js/app.js"></script>';
  const lookup = lookupFrom({ 'https://shop.example.com/js/app.js': { mime: 'text/javascript', body: Buffer.from('console.log("</script> danger")') } });
  const out = inlineAssets(html, BASE, lookup);
  assert.ok(out.startsWith('<script>console.log('));
  assert.ok(!out.includes('</script> danger'));
  assert.ok(out.includes('<\\/script> danger'));
});

test('inlineAssets never touches a script tag that already has inline content', () => {
  const html = '<script src="/x.js">already here</script>';
  const lookup = lookupFrom({ 'https://shop.example.com/x.js': { mime: 'text/javascript', body: Buffer.from('new') } });
  assert.strictEqual(inlineAssets(html, BASE, lookup), html);
});

test('inlineAssets replaces img src with a data URI and drops srcset so it cannot override it', () => {
  const html = '<img src="/img/logo.png" srcset="/img/logo@2x.png 2x" alt="Logo">';
  const lookup = lookupFrom({ 'https://shop.example.com/img/logo.png': { mime: 'image/png', body: Buffer.from('PNGDATA') } });
  const out = inlineAssets(html, BASE, lookup);
  assert.match(out, /^<img src="data:image\/png;base64,[A-Za-z0-9+/=]+" alt="Logo">$/);
  assert.ok(!out.includes('srcset'));
});

test('inlineAssets leaves references untouched when nothing is cached for them', () => {
  const html = '<link rel="stylesheet" href="/css/app.css"><img src="/img/logo.png"><script src="/js/app.js"></script>';
  assert.strictEqual(inlineAssets(html, BASE, () => null), html);
});

test('inlineAssets inlines url() refs inside an inline <style> block too', () => {
  const html = '<style>.a{background:url(/bg.png)}</style>';
  const lookup = lookupFrom({ 'https://shop.example.com/bg.png': { mime: 'image/png', body: Buffer.from('X') } });
  const out = inlineAssets(html, BASE, lookup);
  assert.match(out, /^<style>\.a\{background:url\("data:image\/png;base64,[A-Za-z0-9+/=]+"\)\}<\/style>$/);
});

test('inlineCssUrls leaves data: and unresolvable refs alone', () => {
  const css = 'a{background:url(data:image/gif;base64,R0lGOD)} b{background:url(/x.png)}';
  const out = inlineCssUrls(css, BASE, () => null);
  assert.strictEqual(out, css);
});
