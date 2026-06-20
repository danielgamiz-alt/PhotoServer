'use strict';

/**
 * A small in-memory ring buffer of recent activity (uploads, starts/stops,
 * errors) shown in the dashboard's activity panel.
 */
class ActivityLog {
  constructor(max = 200) {
    this.max = max;
    this.entries = [];
  }

  add(level, message) {
    this.entries.push({ time: Date.now(), level, message });
    if (this.entries.length > this.max) {
      this.entries.splice(0, this.entries.length - this.max);
    }
  }

  /** Most recent first, optionally limited. */
  recent(limit = 100) {
    return this.entries.slice(-limit).reverse();
  }
}

module.exports = { ActivityLog };
