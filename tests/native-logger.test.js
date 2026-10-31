'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const pluginRoot = path.resolve(__dirname, '..');

function loadModule(exec) {
  const module = { exports: {} };
  vm.runInNewContext(
    fs.readFileSync(path.join(pluginRoot, 'www/native-logger.js'), 'utf8'),
    {
      module: module,
      exports: module.exports,
      require: function (name) {
        assert.equal(name, 'cordova/exec');
        return exec;
      },
      Promise: Promise,
      Error: Error,
      Object: Object,
      String: String,
    },
    { filename: 'www/native-logger.js' }
  );
  return module.exports;
}

test('start resolves capabilities and forwards persistent status and entry events', async function () {
  let success;
  let service;
  let action;
  let args;
  const received = [];
  const api = loadModule(function (onSuccess, _onError, serviceName, actionName, callArgs) {
    success = onSuccess;
    service = serviceName;
    action = actionName;
    args = callArgs;
  });

  const start = api.start({ minLevel: 'D' }, function (event) {
    received.push(event);
  });
  assert.equal(service, 'NativeLogger');
  assert.equal(action, 'start');
  assert.equal(args[0].minLevel, 'D');

  const capabilities = { platform: 'android', liveCapture: 'stream' };
  success({ type: 'status', status: 'started', capabilities: capabilities });
  assert.deepEqual(await start, capabilities);

  const entry = { type: 'entry', tag: 'SpeechSynthesis', level: 'W', message: 'warning' };
  success(entry);
  assert.deepEqual(received, [
    { type: 'status', status: 'started', capabilities: capabilities },
    entry,
  ]);
});

test('start rejects coded native errors and can be retried', async function () {
  let error;
  const api = loadModule(function (_onSuccess, onError) {
    error = onError;
  });

  const start = api.start({});
  error({ code: 'ALREADY_STARTED', message: 'Capture is already running' });
  await assert.rejects(start, function (reason) {
    assert.equal(reason.code, 'ALREADY_STARTED');
    assert.equal(reason.message, 'Capture is already running');
    return true;
  });
});

test('one-shot actions preserve names, arguments, and native errors', async function () {
  const calls = [];
  const api = loadModule(function (success, failure, service, action, args) {
    calls.push({ success: success, failure: failure, service: service, action: action, args: args });
  });

  const sinceMs = 1234;
  const exits = api.getExitReasons(sinceMs);
  assert.equal(calls[0].action, 'getExitReasons');
  assert.deepEqual(Array.from(calls[0].args), [sinceMs]);
  calls[0].success([{ timestamp: sinceMs + 1, reason: 'reboot' }]);
  assert.equal((await exits)[0].reason, 'reboot');

  const flush = api.flush();
  assert.equal(calls[1].service, 'NativeLogger');
  assert.equal(calls[1].action, 'flush');
  calls[1].failure('Not started');
  await assert.rejects(flush, function (reason) {
    assert.equal(reason.code, 'NATIVE_ERROR');
    return true;
  });
});

test('shared fixtures use the same timestamped line shape', function () {
  const fixtures = require('./fixtures/log-lines.json');
  assert.equal(fixtures.length, 2);
  fixtures.forEach(function (fixture) {
    assert.match(fixture.line, /^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+\s+[VDIWEF]\s+/);
    assert.ok(fixture.tag);
    assert.ok(fixture.timestamp);
  });
});
