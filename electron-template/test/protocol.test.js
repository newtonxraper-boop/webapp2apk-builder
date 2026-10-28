'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { protocolScheme, parseAuthLink, authLinkFromArgv, isGoogleHandoffUrl } = require('../src/lib/protocol');

const APP = 'https://school.example.com/';

test('protocolScheme: valid URL scheme from any package name', () => {
  assert.strictEqual(protocolScheme('com.webapp2apk.schoolchat'), 'com.webapp2apk.schoolchat');
  assert.strictEqual(protocolScheme('com.My_App.x'), 'com.my-app.x');
  assert.strictEqual(protocolScheme('123.abc'), 'app123.abc');
  assert.match(protocolScheme('weird $$ name'), /^[a-z][a-z0-9+.\-]*$/);
  assert.strictEqual(protocolScheme(''), 'app');
});

test('parseAuthLink: only own-site http(s) addresses behind our scheme and the "auth" host', () => {
  const s = 'com.acme.app';
  const good = 'https://school.example.com/login.php?app_token=abc';
  assert.strictEqual(parseAuthLink(s + '://auth?url=' + encodeURIComponent(good), s, APP), good);
  assert.strictEqual(parseAuthLink(s + '://auth/?url=' + encodeURIComponent(good), s, APP), good);
  assert.strictEqual(parseAuthLink('"' + s + '://auth?url=' + encodeURIComponent(good) + '"', s, APP), good, 'quotes from a command line');
  assert.strictEqual(parseAuthLink('https://www.school.example.com/x'.replace('https://www.', s + '://auth?url=https%3A%2F%2Fwww.'), s, APP), 'https://www.school.example.com/x', 'www/apex of the same site');
  for (const bad of [
    s + '://auth?url=' + encodeURIComponent('https://evil.test/login.php'),
    s + '://auth?url=' + encodeURIComponent('javascript:alert(1)'),
    s + '://auth?url=' + encodeURIComponent('file:///etc/passwd'),
    s + '://other?url=' + encodeURIComponent(good),
    s + '://auth',
    'other.app://auth?url=' + encodeURIComponent(good),
    'not a url', '', null, undefined,
  ]) assert.strictEqual(parseAuthLink(bad, s, APP), null, String(bad));
});

test('authLinkFromArgv finds the link among normal arguments', () => {
  const s = 'com.acme.app';
  const good = 'https://school.example.com/login.php?app_token=abc';
  assert.strictEqual(authLinkFromArgv(['app.exe', '--flag', s + '://auth?url=' + encodeURIComponent(good)], s, APP), good);
  assert.strictEqual(authLinkFromArgv(['app.exe'], s, APP), null);
  assert.strictEqual(authLinkFromArgv(undefined, s, APP), null);
});

test('isGoogleHandoffUrl: only Google sign-in for a flow that returns to the app\'s own site', () => {
  const g = (redirect) => 'https://accounts.google.com/o/oauth2/v2/auth?client_id=1&redirect_uri=' + encodeURIComponent(redirect);
  assert.strictEqual(isGoogleHandoffUrl(g(APP + 'login.php'), APP), true);
  assert.strictEqual(isGoogleHandoffUrl(g('https://www.school.example.com/register.php'), APP), true);
  assert.strictEqual(isGoogleHandoffUrl(g('https://elsewhere.test/cb'), APP), false, 'other site cannot be finished by this app');
  assert.strictEqual(isGoogleHandoffUrl('https://accounts.google.com/o/oauth2/v2/auth?client_id=1', APP), false, 'no redirect_uri (JS popup)');
  assert.strictEqual(isGoogleHandoffUrl('https://accounts.google.com/signin/v2', APP), false);
  assert.strictEqual(isGoogleHandoffUrl(APP + 'login.php?google=1', APP), false, 'the site\'s own link is not Google yet');
  assert.strictEqual(isGoogleHandoffUrl('https://evil.test/o/oauth2/v2/auth?redirect_uri=' + encodeURIComponent(APP), APP), false);
  assert.strictEqual(isGoogleHandoffUrl('nonsense', APP), false);
});
