'use strict';
const fs = require('node:fs');
const path = require('node:path');
const serverRoot = path.resolve(__dirname, '..');
const output = path.resolve(serverRoot, 'dist');
if (path.dirname(output) !== serverRoot || path.basename(output) !== 'dist') throw new Error('Unexpected output directory');
fs.rmSync(output, { recursive: true, force: true });
