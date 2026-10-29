PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT UNIQUE,
  password_hash TEXT,
  phone TEXT,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS vehicles (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  name TEXT NOT NULL,
  make TEXT,
  model TEXT,
  year INTEGER,
  fuel_type TEXT,
  odometer_km REAL,
  status TEXT DEFAULT 'healthy',
  health_score INTEGER DEFAULT 0,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS trips (
  id TEXT PRIMARY KEY,
  vehicle_id TEXT NOT NULL,
  route_name TEXT,
  start_time TEXT,
  end_time TEXT,
  distance_km REAL,
  fuel_used_liters REAL,
  cost_sar REAL,
  avg_consumption_l_100km REAL,
  avg_speed_kmh REAL,
  max_speed_kmh REAL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);

CREATE TABLE IF NOT EXISTS maintenance_tasks (
  id TEXT PRIMARY KEY,
  vehicle_id TEXT NOT NULL,
  item_name TEXT NOT NULL,
  due_in_km REAL,
  due_in_days INTEGER,
  status TEXT NOT NULL DEFAULT 'scheduled',
  urgency TEXT NOT NULL DEFAULT 'normal',
  last_done_at TEXT,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);

CREATE TABLE IF NOT EXISTS diagnostic_codes (
  id TEXT PRIMARY KEY,
  vehicle_id TEXT NOT NULL,
  code TEXT NOT NULL,
  title TEXT NOT NULL,
  detail TEXT,
  severity TEXT NOT NULL DEFAULT 'medium',
  status TEXT NOT NULL DEFAULT 'open',
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);

CREATE TABLE IF NOT EXISTS alerts (
  id TEXT PRIMARY KEY,
  vehicle_id TEXT NOT NULL,
  alert_type TEXT NOT NULL,
  title TEXT NOT NULL,
  detail TEXT,
  severity TEXT NOT NULL DEFAULT 'info',
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);

CREATE TABLE IF NOT EXISTS health_scores (
  id TEXT PRIMARY KEY,
  vehicle_id TEXT NOT NULL,
  score INTEGER NOT NULL,
  fuel_efficiency_l_100km REAL,
  baseline_l_100km REAL,
  trend_percent REAL,
  engine_avg_rpm REAL,
  idle_time_percent REAL,
  summary TEXT,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);

CREATE TABLE IF NOT EXISTS sensor_snapshots (
  id TEXT PRIMARY KEY,
  vehicle_id TEXT NOT NULL,
  metric_name TEXT NOT NULL,
  value REAL,
  unit TEXT,
  captured_at TEXT NOT NULL,
  FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);

CREATE TABLE IF NOT EXISTS sync_log (
  id TEXT PRIMARY KEY,
  vehicle_id TEXT NOT NULL,
  payload_type TEXT NOT NULL,
  payload_json TEXT,
  synced_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);

INSERT OR IGNORE INTO users (id, name, email, phone) VALUES
  ('user-001', 'أحمد السالم', 'ahmed@example.com', '+966500000001');

INSERT OR IGNORE INTO vehicles (id, user_id, name, make, model, year, fuel_type, odometer_km, status, health_score)
VALUES
  ('veh-001', 'user-001', 'Toyota Corolla 2021', 'Toyota', 'Corolla', 2021, 'gasoline', 28450, 'healthy', 82);

INSERT OR IGNORE INTO trips (id, vehicle_id, route_name, start_time, end_time, distance_km, fuel_used_liters, cost_sar, avg_consumption_l_100km, avg_speed_kmh, max_speed_kmh)
VALUES
  ('trip-001', 'veh-001', 'الرياض → الخبر', '2026-08-29T08:10:00', '2026-08-29T10:50:00', 455, 42, 218, 9.2, 112, 128),
  ('trip-002', 'veh-001', 'الخبر → جدة', '2026-08-30T09:00:00', '2026-08-30T11:30:00', 320, 29, 154, 9.1, 98, 120),
  ('trip-003', 'veh-001', 'جدة → المدينة', '2026-08-31T07:40:00', '2026-08-31T10:55:00', 510, 44, 232, 8.6, 108, 132);

INSERT OR IGNORE INTO maintenance_tasks (id, vehicle_id, item_name, due_in_km, due_in_days, status, urgency)
VALUES
  ('maint-001', 'veh-001', 'تغيير الزيت', 180, 15, 'scheduled', 'normal'),
  ('maint-002', 'veh-001', 'فحص الإطارات', 120, 12, 'scheduled', 'normal'),
  ('maint-003', 'veh-001', 'بطارية السيارة', 0, 10, 'warning', 'warning'),
  ('maint-004', 'veh-001', 'فلتر الهواء', 700, 35, 'recommended', 'low');

INSERT OR IGNORE INTO diagnostic_codes (id, vehicle_id, code, title, detail, severity, status)
VALUES
  ('diag-001', 'veh-001', 'P0301', 'فقد إشعال في الأسطوانة 1 (Cylinder 1 Misfire)', 'مؤشرات احتراق غير منتظمة في الأسطوانة الأولى.', 'medium', 'open'),
  ('diag-002', 'veh-001', 'P0560', 'اضطراب جهد نظام الكهرباء والبطارية (System Voltage)', 'قيمة الجهد متغيرة وتحتاج فحص شحن الدينامو والبطارية.', 'low', 'monitoring'),
  ('diag-003', 'veh-001', 'P0420', 'انخفاض كفاءة علبة البيئة (المحول الحفاز Catalyst System)', 'كفاءة المحول الحفاز أقل من الحد القياسي.', 'high', 'review');

INSERT OR IGNORE INTO alerts (id, vehicle_id, alert_type, title, detail, severity)
VALUES
  ('alert-001', 'veh-001', 'warning', 'ارتفاع حرارة المحرك', 'تم اكتشاف ارتفاع غير طبيعي خلال الرحلة الأخيرة.', 'warning'),
  ('alert-002', 'veh-001', 'info', 'استهلاك وقود أعلى من المتوسط', 'استهلاكك ظهر أعلى من المتوسط مقارنة بالشهر الماضي.', 'info'),
  ('alert-003', 'veh-001', 'safe', 'حالة البطارية مستقرة', 'جهد البطارية ضمن المعدل الطبيعي.', 'safe');

INSERT OR IGNORE INTO health_scores (id, vehicle_id, score, fuel_efficiency_l_100km, baseline_l_100km, trend_percent, engine_avg_rpm, idle_time_percent, summary)
VALUES
  ('health-001', 'veh-001', 82, 8.6, 7.9, 8.9, 1950, 12, 'هناك مؤشر صغير على ارتفاع استهلاك الوقود مقارنة بالمتوسط الطبيعي، لكن السيارة لا تزال ضمن النطاق المقبول.');

INSERT OR IGNORE INTO sensor_snapshots (id, vehicle_id, metric_name, value, unit, captured_at)
VALUES
  ('snap-001', 'veh-001', 'fuel_level', 68, '%', '2026-08-31T21:30:00'),
  ('snap-002', 'veh-001', 'battery_voltage', 13.9, 'V', '2026-08-31T21:30:00'),
  ('snap-003', 'veh-001', 'engine_temp', 88, 'C', '2026-08-31T21:30:00'),
  ('snap-004', 'veh-001', 'speed', 62, 'km/h', '2026-08-31T21:30:00');
