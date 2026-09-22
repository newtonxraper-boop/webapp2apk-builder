'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');

function tmpDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix || 'w2a-'));
}
module.exports = { tmpDir };
