import fs from 'node:fs';
import path from 'node:path';

export type AlarmTimeZone = 'Asia/Shanghai' | 'UTC';
export function validAlarmTimeZone(value: unknown): value is AlarmTimeZone {
  return value === 'Asia/Shanghai' || value === 'UTC';
}

/** One display standard per account; event instants and deadlines stay unchanged. */
export class TimeStandardStore {
  private timeZone: AlarmTimeZone = 'Asia/Shanghai';
  constructor(private readonly file: string) {
    if (!fs.existsSync(file)) return;
    const value = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (!value || !validAlarmTimeZone(value.timeZone)) throw new Error('Invalid alarm time standard');
    this.timeZone = value.timeZone;
  }
  get(): { timeZone: AlarmTimeZone; serverTime: string } {
    return { timeZone: this.timeZone, serverTime: new Date().toISOString() };
  }
  set(timeZone: AlarmTimeZone): void {
    if (!validAlarmTimeZone(timeZone)) throw new Error('Invalid alarm time zone');
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const temporary = `${this.file}.tmp`;
    try {
      const fd = fs.openSync(temporary, 'w', 0o600);
      try { fs.writeFileSync(fd, JSON.stringify({ timeZone }), 'utf8'); fs.fsyncSync(fd); }
      finally { fs.closeSync(fd); }
      fs.renameSync(temporary, this.file);
    } finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); }
    this.timeZone = timeZone;
  }
}
