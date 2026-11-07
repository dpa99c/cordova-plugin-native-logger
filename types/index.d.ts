export type NativeLogLevel = 'V' | 'D' | 'I' | 'W' | 'E' | 'F';

export interface NativeLoggerOptions {
  minLevel?: NativeLogLevel;
  includeTags?: string[];
  excludeTags?: string[];
  includeAllMinLevel?: NativeLogLevel;
  forward?: {
    minLevel?: NativeLogLevel;
    tags?: string[];
    excludeTags?: string[];
  };
  maxAgeMs?: number;
  maxFileSizeBytes?: number;
  /** Maximum message characters written to native files; 0 or omitted means unlimited. Forwarded events stay full length. */
  maxMessageLength?: number;
  ios?: {
    pollIntervalMs?: number;
    captureStdio?: boolean;
  };
}

export interface NativeLoggerCapabilities {
  platform: 'android' | 'ios';
  liveCapture: 'stream' | 'poll';
  previousProcessLogs: boolean;
  exitReasons: 'system' | 'inferred';
  crashReports: 'logcat' | 'metrickit';
  stdio: boolean;
}

export interface NativeLogEntry {
  type: 'entry';
  timestamp: string;
  pid: number;
  tid: number;
  level: NativeLogLevel;
  tag: string;
  message: string;
}

export interface NativeLoggerStatus {
  type: 'status';
  status: string;
  capabilities?: NativeLoggerCapabilities;
  ignoredOptions?: string[];
  message?: string;
}

export interface NativeExitReason {
  timestamp: number;
  pid: number;
  reason: string;
  description: string;
  source: 'system' | 'inferred' | 'metrickit';
  details: Record<string, unknown>;
}

export interface NativeLoggerError extends Error {
  code: string;
}

export interface NativeLoggerPlugin {
  start(
    options?: NativeLoggerOptions,
    onEvent?: (event: NativeLogEntry | NativeLoggerStatus) => void
  ): Promise<NativeLoggerCapabilities>;
  stop(): Promise<void>;
  flush(): Promise<void>;
  resetLogFile(): Promise<void>;
  pruneLogFile(maxAgeMs: number): Promise<void>;
  getExitReasons(sinceMs?: number): Promise<NativeExitReason[]>;
  getDiagnostics(): Promise<Record<string, unknown>>;
  exportLog(): Promise<string>;
  getCapabilities(): Promise<NativeLoggerCapabilities>;
}

declare global {
  interface CordovaPlugins {
    nativeLogger: NativeLoggerPlugin;
  }
}

export {};
