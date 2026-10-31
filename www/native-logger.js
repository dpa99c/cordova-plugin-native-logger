'use strict';

var exec = require('cordova/exec');
var SERVICE = 'NativeLogger';
var startPending = false;
var started = false;

function normaliseError(error) {
  var source = error || {};
  var message = typeof source === 'string'
    ? source
    : source.message || 'Native logger operation failed';
  var result = new Error(message);
  result.code = source.code || 'NATIVE_ERROR';
  return result;
}

function invoke(action, args) {
  return new Promise(function (resolve, reject) {
    exec(
      resolve,
      function (error) {
        reject(normaliseError(error));
      },
      SERVICE,
      action,
      args || []
    );
  });
}

var NativeLogger = {
  /** Starts native capture and resolves with this platform's capabilities. */
  start: function (options, onEvent) {
    if (startPending || started) {
      return Promise.reject(Object.assign(new Error('Native logger is already started'), {
        code: 'ALREADY_STARTED',
      }));
    }

    startPending = true;
    return new Promise(function (resolve, reject) {
      var settled = false;
      exec(
        function (event) {
          if (event && event.type === 'status' && event.status === 'started' && !settled) {
            settled = true;
            startPending = false;
            started = true;
            resolve(event.capabilities);
          }
          if (event && event.type === 'status' && event.status === 'stopped') {
            started = false;
          }
          if (typeof onEvent === 'function') {
            onEvent(event);
          }
        },
        function (error) {
          startPending = false;
          started = false;
          if (!settled) {
            settled = true;
            reject(normaliseError(error));
          } else if (typeof onEvent === 'function') {
            onEvent({
              type: 'status',
              status: 'error',
              error: normaliseError(error),
            });
          }
        },
        SERVICE,
        'start',
        [options || {}]
      );
    });
  },

  /** Stops capture and closes the native log file. */
  stop: function () {
    return invoke('stop').then(function (result) {
      started = false;
      return result;
    });
  },

  /** Flushes captured entries to durable storage. */
  flush: function () {
    return invoke('flush');
  },

  /** Returns process-exit records newer than the supplied Unix timestamp. */
  getExitReasons: function (sinceMs) {
    return invoke('getExitReasons', [sinceMs || 0]);
  },

  /** Returns the current device and application diagnostics snapshot. */
  getDiagnostics: function () {
    return invoke('getDiagnostics');
  },

  /** Exports a combined copy of the current and rotated logs and returns its file URL. */
  exportLog: function () {
    return invoke('exportLog');
  },

  /** Returns the capabilities supported by the current native platform. */
  getCapabilities: function () {
    return invoke('getCapabilities');
  },
};

module.exports = NativeLogger;
