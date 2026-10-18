import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import uuid
from datetime import datetime, timedelta, timezone
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

ROOT = os.path.dirname(__file__)
PUBLIC_DIR = os.path.join(ROOT, 'public')
PORT = int(os.environ.get('DRIVECARE_PORT', '3000'))
HOST = os.environ.get('DRIVECARE_HOST', '127.0.0.1')
SESSION_TTL_HOURS = 24
MAX_JSON_BYTES = 64 * 1024
DB_PATH = os.path.join(ROOT, 'car_health.db')
SCHEMA_PATH = os.path.join(ROOT, 'schema.sql')


class DatabaseConnection(sqlite3.Connection):
    """Close SQLite handles when a ``with`` block ends, including on Windows."""

    def __exit__(self, exc_type, exc_value, traceback):
        try:
            return super().__exit__(exc_type, exc_value, traceback)
        finally:
            self.close()


def get_connection():
    conn = sqlite3.connect(DB_PATH, factory=DatabaseConnection)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA foreign_keys = ON')
    return conn


def init_db():
    created = not os.path.exists(DB_PATH)
    conn = get_connection()
    if created:
        with open(SCHEMA_PATH, 'r', encoding='utf-8') as schema_file:
            conn.executescript(schema_file.read())
    columns = conn.execute("PRAGMA table_info(users)").fetchall()
    column_names = [col[1] for col in columns]
    if 'password_hash' not in column_names:
        conn.execute('ALTER TABLE users ADD COLUMN password_hash TEXT')
    conn.executescript('''
        CREATE TABLE IF NOT EXISTS workshops (
          id TEXT PRIMARY KEY,
          owner_user_id TEXT NOT NULL,
          name TEXT NOT NULL,
          services TEXT NOT NULL,
          phone TEXT NOT NULL,
          latitude REAL,
          longitude REAL,
          radius_km REAL DEFAULT 20,
          created_at TEXT DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (owner_user_id) REFERENCES users(id)
        );
        CREATE TABLE IF NOT EXISTS workshop_access_requests (
          id TEXT PRIMARY KEY,
          workshop_id TEXT NOT NULL,
          owner_user_id TEXT NOT NULL,
          vehicle_id TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'pending',
          permissions TEXT NOT NULL,
          expires_at TEXT,
          created_at TEXT DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (workshop_id) REFERENCES workshops(id),
          FOREIGN KEY (owner_user_id) REFERENCES users(id),
          FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
        );
        CREATE TABLE IF NOT EXISTS vehicle_drivers (
          id TEXT PRIMARY KEY,
          owner_user_id TEXT NOT NULL,
          vehicle_id TEXT NOT NULL,
          driver_user_id TEXT NOT NULL,
          can_request_help INTEGER NOT NULL DEFAULT 1,
          can_share_data INTEGER NOT NULL DEFAULT 0,
          status TEXT NOT NULL DEFAULT 'active',
          created_at TEXT DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (owner_user_id) REFERENCES users(id),
          FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
          FOREIGN KEY (driver_user_id) REFERENCES users(id)
        );
        CREATE TABLE IF NOT EXISTS user_roles (
          user_id TEXT NOT NULL,
          role TEXT NOT NULL,
          created_at TEXT DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (user_id, role),
          FOREIGN KEY (user_id) REFERENCES users(id)
        );
        CREATE TABLE IF NOT EXISTS vehicle_device_access (
          id TEXT PRIMARY KEY,
          owner_user_id TEXT NOT NULL,
          vehicle_id TEXT NOT NULL,
          driver_user_id TEXT NOT NULL,
          permissions TEXT NOT NULL DEFAULT '["pair"]',
          status TEXT NOT NULL DEFAULT 'active',
          expires_at TEXT NOT NULL,
          created_at TEXT DEFAULT CURRENT_TIMESTAMP,
          revoked_at TEXT,
          FOREIGN KEY (owner_user_id) REFERENCES users(id),
          FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
          FOREIGN KEY (driver_user_id) REFERENCES users(id)
        );
        CREATE TABLE IF NOT EXISTS sessions (
          token_hash TEXT PRIMARY KEY,
          user_id TEXT NOT NULL,
          expires_at TEXT NOT NULL,
          revoked_at TEXT,
          created_at TEXT DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (user_id) REFERENCES users(id)
        );
        CREATE INDEX IF NOT EXISTS sessions_active_user_idx
          ON sessions (user_id, expires_at);
        CREATE TABLE IF NOT EXISTS audit_log (
          id TEXT PRIMARY KEY,
          actor_user_id TEXT,
          action TEXT NOT NULL,
          subject_type TEXT,
          subject_id TEXT,
          created_at TEXT DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (actor_user_id) REFERENCES users(id)
        );
    ''')
    conn.commit()
    conn.close()


def hash_password(password):
    """Return a versioned, salted scrypt password hash for new credentials."""
    if not isinstance(password, str) or not password:
        raise ValueError('Password is required')
    salt = secrets.token_bytes(16)
    derived = hashlib.scrypt(password.encode('utf-8'), salt=salt, n=2**14, r=8, p=1)
    return 'scrypt$16384$8$1${}${}'.format(salt.hex(), derived.hex())


def verify_password(password, stored_hash):
    """Verify either the current scrypt format or a legacy SHA-256 hash.

    The second return value tells the caller to upgrade a legacy hash after a successful login.
    """
    if not isinstance(password, str) or not isinstance(stored_hash, str):
        return False, False
    parts = stored_hash.split('$')
    if len(parts) == 6 and parts[0] == 'scrypt':
        try:
            derived = hashlib.scrypt(
                password.encode('utf-8'), salt=bytes.fromhex(parts[4]),
                n=int(parts[1]), r=int(parts[2]), p=int(parts[3])
            ).hex()
        except (TypeError, ValueError):
            return False, False
        return hmac.compare_digest(derived, parts[5]), False
    legacy = hashlib.sha256(password.encode('utf-8')).hexdigest()
    return hmac.compare_digest(legacy, stored_hash), True


VALID_ROLES = {'owner', 'driver', 'workshop'}


def normalize_roles(roles):
    if not isinstance(roles, list):
        return ['owner']
    normalized = [str(role).strip().lower() for role in roles]
    selected = [role for role in normalized if role in VALID_ROLES]
    return list(dict.fromkeys(selected)) or ['owner']


def save_user_roles(user_id, roles):
    with get_connection() as conn:
        conn.executemany(
            'INSERT OR IGNORE INTO user_roles (user_id, role) VALUES (?, ?)',
            [(user_id, role) for role in normalize_roles(roles)]
        )
        conn.commit()


def fetch_user_roles(user_id):
    with get_connection() as conn:
        rows = conn.execute(
            'SELECT role FROM user_roles WHERE user_id = ? ORDER BY role', (user_id,)
        ).fetchall()
    roles = [row['role'] for row in rows]
    return roles or ['owner']


def create_session(user_id):
    token = secrets.token_urlsafe(32)
    expires_at = (utc_now() + timedelta(hours=SESSION_TTL_HOURS)).isoformat()
    with get_connection() as conn:
        conn.execute(
            'INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)',
            (hashlib.sha256(token.encode('utf-8')).hexdigest(), user_id, expires_at)
        )
        conn.commit()
    return token


def require_auth(handler):
    token = handler.headers.get('Authorization') or handler.headers.get('X-Auth-Token')
    if token and token.lower().startswith('bearer '):
        token = token[7:].strip()
    if not token:
        return None
    token_hash = hashlib.sha256(token.encode('utf-8')).hexdigest()
    with get_connection() as conn:
        row = conn.execute(
            '''SELECT user_id FROM sessions
               WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?''',
            (token_hash, utc_now().isoformat())
        ).fetchone()
    return row['user_id'] if row else None


def revoke_session(handler):
    token = handler.headers.get('Authorization') or handler.headers.get('X-Auth-Token')
    if token and token.lower().startswith('bearer '):
        token = token[7:].strip()
    if not token:
        return False
    with get_connection() as conn:
        updated = conn.execute(
            'UPDATE sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL',
            (utc_now().isoformat(), hashlib.sha256(token.encode('utf-8')).hexdigest())
        ).rowcount
        conn.commit()
    return updated > 0


def audit(actor_user_id, action, subject_type=None, subject_id=None):
    with get_connection() as conn:
        conn.execute(
            '''INSERT INTO audit_log (id, actor_user_id, action, subject_type, subject_id)
               VALUES (?, ?, ?, ?, ?)''',
            (f'audit-{uuid.uuid4().hex}', actor_user_id, action, subject_type, subject_id)
        )
        conn.commit()


def fetch_user_by_email(email):
    with get_connection() as conn:
        row = conn.execute('SELECT * FROM users WHERE email = ?', (email,)).fetchone()
    return dict(row) if row else None


def fetch_user_by_phone(phone):
    with get_connection() as conn:
        row = conn.execute('SELECT * FROM users WHERE phone = ?', (phone,)).fetchone()
    return dict(row) if row else None


def fetch_user_by_id(user_id):
    with get_connection() as conn:
        row = conn.execute('SELECT * FROM users WHERE id = ?', (user_id,)).fetchone()
    return dict(row) if row else None


def fetch_user_vehicles(user_id):
    with get_connection() as conn:
        rows = conn.execute(
            '''
            SELECT id, name, make, model, year, fuel_type AS fuelType,
                   odometer_km AS odometerKm, status, health_score AS healthScore
            FROM vehicles WHERE user_id = ? ORDER BY created_at DESC
            ''', (user_id,)
        ).fetchall()
    return [dict(row) for row in rows]


def create_vehicle_for_user(user_id, data):
    vehicle_id = data.get('id') or f'veh-{uuid.uuid4().hex[:8]}'
    name = data.get('name', 'My Car')
    make = data.get('make', 'Unknown')
    model = data.get('model', 'Model')
    year = data.get('year', 2024)
    fuel_type = data.get('fuelType', 'gasoline')
    odometer_km = data.get('odometerKm', 0)
    status = data.get('status', 'healthy')
    health_score = data.get('healthScore', 80)
    with get_connection() as conn:
        existing = conn.execute('SELECT user_id FROM vehicles WHERE id = ?', (vehicle_id,)).fetchone()
        if existing and existing['user_id'] != user_id:
            raise ValueError('Vehicle ID already belongs to another owner')
        conn.execute(
            '''
            INSERT OR REPLACE INTO vehicles (
                id, user_id, name, make, model, year, fuel_type,
                odometer_km, status, health_score
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', (vehicle_id, user_id, name, make, model, year, fuel_type, odometer_km, status, health_score)
        )
        conn.commit()
    return vehicle_id


def utc_now():
    return datetime.now(timezone.utc)


def parse_expiry(value):
    try:
        expiry = datetime.fromisoformat(str(value).replace('Z', '+00:00'))
        if expiry.tzinfo is None:
            expiry = expiry.replace(tzinfo=timezone.utc)
        return expiry
    except (TypeError, ValueError):
        return None


def fetch_device_access(user_id, vehicle_id=None):
    query = '''
        SELECT a.id, a.vehicle_id AS vehicleId, v.name AS vehicleName,
               a.driver_user_id AS driverId, u.name AS driverName, u.phone AS driverPhone,
               a.permissions, a.status, a.expires_at AS expiresAt, a.created_at AS createdAt
        FROM vehicle_device_access a
        JOIN vehicles v ON v.id = a.vehicle_id
        JOIN users u ON u.id = a.driver_user_id
        WHERE (a.owner_user_id = ? OR a.driver_user_id = ?)
    '''
    params = [user_id, user_id]
    if vehicle_id:
        query += ' AND a.vehicle_id = ?'
        params.append(vehicle_id)
    query += ' ORDER BY a.created_at DESC'
    with get_connection() as conn:
        rows = conn.execute(query, params).fetchall()
    result = []
    now = utc_now()
    for row in rows:
        item = dict(row)
        expiry = parse_expiry(item['expiresAt'])
        if item['status'] == 'active' and (not expiry or expiry <= now):
            item['status'] = 'expired'
        item['permissions'] = json.loads(item['permissions'])
        result.append(item)
    return result


def grant_device_access(owner_user_id, data):
    vehicle_id = data.get('vehicleId')
    driver_id = data.get('driverId')
    expires_at = parse_expiry(data.get('expiresAt'))
    if not vehicle_id or not driver_id or not expires_at or expires_at <= utc_now():
        return None, 'Vehicle, driver, and a future expiry time are required'
    with get_connection() as conn:
        vehicle = conn.execute(
            'SELECT id FROM vehicles WHERE id = ? AND user_id = ?',
            (vehicle_id, owner_user_id)
        ).fetchone()
        driver = conn.execute('SELECT id FROM users WHERE id = ?', (driver_id,)).fetchone()
        assignment = conn.execute(
            '''SELECT id FROM vehicle_drivers
               WHERE owner_user_id = ? AND vehicle_id = ? AND driver_user_id = ? AND status = 'active' ''',
            (owner_user_id, vehicle_id, driver_id)
        ).fetchone()
        if not vehicle:
            return None, 'Only the vehicle owner can grant device access'
        if not driver or not assignment:
            return None, 'Driver is not assigned to this vehicle'
        conn.execute(
            '''UPDATE vehicle_device_access SET status = 'revoked', revoked_at = CURRENT_TIMESTAMP
               WHERE owner_user_id = ? AND vehicle_id = ? AND driver_user_id = ? AND status = 'active' ''',
            (owner_user_id, vehicle_id, driver_id)
        )
        access_id = f'device-access-{uuid.uuid4().hex[:8]}'
        conn.execute(
            '''INSERT INTO vehicle_device_access
               (id, owner_user_id, vehicle_id, driver_user_id, permissions, expires_at)
               VALUES (?, ?, ?, ?, ?, ?)''',
            (access_id, owner_user_id, vehicle_id, driver_id,
             json.dumps(['pair']), expires_at.isoformat())
        )
        conn.commit()
    return access_id, None


def revoke_device_access(owner_user_id, access_id):
    with get_connection() as conn:
        updated = conn.execute(
            '''UPDATE vehicle_device_access SET status = 'revoked', revoked_at = CURRENT_TIMESTAMP
               WHERE id = ? AND owner_user_id = ? AND status = 'active' ''',
            (access_id, owner_user_id)
        ).rowcount
        conn.commit()
    return updated > 0


def driver_has_device_access(driver_user_id, vehicle_id):
    with get_connection() as conn:
        row = conn.execute(
            '''SELECT id, expires_at AS expiresAt FROM vehicle_device_access
               WHERE driver_user_id = ? AND vehicle_id = ? AND status = 'active'
               ORDER BY created_at DESC LIMIT 1''',
            (driver_user_id, vehicle_id)
        ).fetchone()
    if not row:
        return False
    expiry = parse_expiry(row['expiresAt'])
    return bool(expiry and expiry > utc_now())


def export_vehicle_data(user_id):
    with get_connection() as conn:
        rows = conn.execute(
            '''
            SELECT 'vehicles' AS type, id, user_id, name FROM vehicles WHERE user_id = ?
            UNION ALL
            SELECT 'trips', id, vehicle_id, route_name FROM trips WHERE vehicle_id IN (SELECT id FROM vehicles WHERE user_id = ?)
            UNION ALL
            SELECT 'maintenance', id, vehicle_id, item_name FROM maintenance_tasks WHERE vehicle_id IN (SELECT id FROM vehicles WHERE user_id = ?)
            UNION ALL
            SELECT 'diagnostics', id, vehicle_id, code FROM diagnostic_codes WHERE vehicle_id IN (SELECT id FROM vehicles WHERE user_id = ?)
            ''', (user_id, user_id, user_id, user_id)
        ).fetchall()
    return [dict(row) for row in rows]


def fetch_vehicle(vehicle_id='veh-001'):
    with get_connection() as conn:
        row = conn.execute(
            '''
            SELECT id, name, status, health_score AS healthScore,
                   68 AS fuelLevel, 55 AS tankCapacityLiters,
                   9.0 AS currentConsumption, ROUND((68 * 55 / 100) / 9.0 * 100, 0) AS estimatedRangeKm,
                   13.9 AS battery, 88 AS engineTemp, 62 AS speed,
                   'اليوم 18:40' AS lastTrip, 'في 380 كم' AS nextService,
                   odometer_km AS odometer, 'km' AS unit
            FROM vehicles WHERE id = ?
            ''', (vehicle_id,)
        ).fetchone()
        if row is None:
            return {}
        return dict(row)


def fetch_trips(vehicle_id='veh-001'):
    with get_connection() as conn:
        rows = conn.execute(
            '''
            SELECT id, route_name AS route,
                   substr(start_time, 1, 10) AS date,
                   distance_km AS distance,
                   fuel_used_liters AS fuelUsed,
                   cost_sar AS cost,
                   avg_consumption_l_100km AS average
            FROM trips WHERE vehicle_id = ? ORDER BY start_time DESC
            ''', (vehicle_id,)
        ).fetchall()
    return [dict(row) for row in rows]


def fetch_maintenance(vehicle_id='veh-001'):
    with get_connection() as conn:
        rows = conn.execute(
            '''
            SELECT id, item_name AS item,
                   CASE
                     WHEN due_in_km > 0 THEN 'في ' || due_in_km || ' كم'
                     WHEN due_in_days > 0 THEN 'خلال ' || due_in_days || ' أيام'
                     ELSE 'قريباً'
                   END AS dueIn,
                   status, urgency
            FROM maintenance_tasks WHERE vehicle_id = ? ORDER BY created_at DESC
            ''', (vehicle_id,)
        ).fetchall()
    return [dict(row) for row in rows]


def fetch_diagnostics(vehicle_id='veh-001'):
    with get_connection() as conn:
        rows = conn.execute(
            '''
            SELECT id, code, title, severity, detail AS summary, status
            FROM diagnostic_codes WHERE vehicle_id = ? ORDER BY created_at DESC
            ''', (vehicle_id,)
        ).fetchall()
    return [dict(row) for row in rows]


def fetch_alerts(vehicle_id='veh-001'):
    with get_connection() as conn:
        rows = conn.execute(
            '''
            SELECT id, alert_type AS type, title, detail,
                   CASE
                     WHEN severity = 'warning' THEN '30 دقيقة مضت'
                     WHEN severity = 'info' THEN '2 ساعات مضت'
                     ELSE 'الآن'
                   END AS time
            FROM alerts WHERE vehicle_id = ? ORDER BY created_at DESC
            ''', (vehicle_id,)
        ).fetchall()
    return [dict(row) for row in rows]


def fetch_garage_vehicles(workshop_user_id):
    """Return only vehicles whose owner approved this workshop's requested fields."""
    with get_connection() as conn:
        rows = conn.execute(
            '''
            SELECT v.id AS vehicleId, v.name AS vehicleName, v.status,
                   v.health_score AS healthScore, r.permissions,
                   COALESCE((SELECT COUNT(*) FROM maintenance_tasks mt WHERE mt.vehicle_id = v.id), 0) AS maintenanceCount,
                   COALESCE((SELECT COUNT(*) FROM diagnostic_codes dc WHERE dc.vehicle_id = v.id), 0) AS diagnosticsCount
            FROM workshop_access_requests r
            JOIN workshops w ON w.id = r.workshop_id
            JOIN vehicles v ON v.id = r.vehicle_id
            WHERE w.owner_user_id = ? AND r.status = 'approved'
              AND (r.expires_at IS NULL OR r.expires_at > ?)
            ORDER BY v.name
            ''', (workshop_user_id, utc_now().isoformat())
        ).fetchall()
    visible = []
    for row in rows:
        permissions = set(json.loads(row['permissions']))
        visible.append({
            'vehicleId': row['vehicleId'],
            'vehicleName': row['vehicleName'],
            'status': row['status'],
            'healthScore': row['healthScore'] if 'health' in permissions else None,
            'maintenanceCount': row['maintenanceCount'] if 'maintenance' in permissions else None,
            'diagnosticsCount': row['diagnosticsCount'] if 'diagnostics' in permissions else None,
            'permissions': sorted(permissions),
        })
    return visible


def fetch_garage_reports(workshop_user_id):
    with get_connection() as conn:
        rows = conn.execute(
            '''
            SELECT v.id AS vehicleId, v.name AS vehicleName, r.permissions,
                   hs.score AS healthScore,
                   hs.summary AS summary,
                   COALESCE((SELECT COUNT(*) FROM diagnostic_codes dc WHERE dc.vehicle_id = v.id), 0) AS diagnosticsCount
            FROM workshop_access_requests r
            JOIN workshops w ON w.id = r.workshop_id
            JOIN vehicles v ON v.id = r.vehicle_id
            LEFT JOIN health_scores hs ON hs.vehicle_id = v.id AND hs.id = (
                SELECT id FROM health_scores WHERE vehicle_id = v.id ORDER BY created_at DESC LIMIT 1
            )
            WHERE w.owner_user_id = ? AND r.status = 'approved'
              AND (r.expires_at IS NULL OR r.expires_at > ?)
            ORDER BY v.name
            ''', (workshop_user_id, utc_now().isoformat())
        ).fetchall()
    reports = []
    for row in rows:
        permissions = set(json.loads(row['permissions']))
        reports.append({
            'vehicleId': row['vehicleId'],
            'vehicleName': row['vehicleName'],
            'healthScore': row['healthScore'] if 'health' in permissions else None,
            'summary': row['summary'] if 'health' in permissions else None,
            'diagnosticsCount': row['diagnosticsCount'] if 'diagnostics' in permissions else None,
            'permissions': sorted(permissions),
        })
    return reports


def fetch_workshops():
    with get_connection() as conn:
        rows = conn.execute(
            'SELECT id, name, services, phone, latitude, longitude, radius_km AS radiusKm FROM workshops ORDER BY name'
        ).fetchall()
    return [dict(row) for row in rows]


def create_workshop(user_id, data):
    workshop_id = f'workshop-{uuid.uuid4().hex[:8]}'
    with get_connection() as conn:
        conn.execute(
            '''INSERT INTO workshops (id, owner_user_id, name, services, phone, latitude, longitude, radius_km)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)''',
            (
                workshop_id,
                user_id,
                (data.get('name') or '').strip(),
                (data.get('services') or '').strip(),
                (data.get('phone') or '').strip(),
                data.get('latitude'),
                data.get('longitude'),
                data.get('radiusKm', 20)
            )
        )
        conn.commit()
    return workshop_id


def add_account_role(user_id, role, workshop=None):
    """Add one role to the authenticated account without creating another user."""
    role = str(role or '').strip().lower()
    if role not in VALID_ROLES:
        return None, 'Unsupported role'
    if role == 'workshop':
        workshop = workshop or {}
        required = ['name', 'services', 'phone']
        if any(not str(workshop.get(key) or '').strip() for key in required):
            return None, 'Workshop name, phone, and services are required'
        with get_connection() as conn:
            exists = conn.execute(
                'SELECT id FROM workshops WHERE owner_user_id = ?', (user_id,)
            ).fetchone()
        if not exists:
            create_workshop(user_id, workshop)
    save_user_roles(user_id, [role])
    return fetch_user_roles(user_id), None


def create_access_request(user_id, data):
    vehicle_id = resolve_user_vehicle_id(user_id, data.get('vehicleId'))
    if not vehicle_id:
        return None
    request_id = f'access-{uuid.uuid4().hex[:8]}'
    permissions = data.get('permissions') or ['health', 'diagnostics', 'maintenance']
    with get_connection() as conn:
        vehicle_owner = conn.execute('SELECT user_id FROM vehicles WHERE id = ?', (vehicle_id,)).fetchone()
        workshop = conn.execute('SELECT id FROM workshops WHERE id = ?', (data.get('workshopId'),)).fetchone()
        if not workshop:
            return None
        conn.execute(
            '''INSERT INTO workshop_access_requests
               (id, workshop_id, owner_user_id, vehicle_id, status, permissions, expires_at)
               VALUES (?, ?, ?, ?, 'pending', ?, ?)''',
            (request_id, data.get('workshopId'), vehicle_owner['user_id'], vehicle_id, json.dumps(permissions), data.get('expiresAt'))
        )
        conn.commit()
    return request_id


def create_workshop_access_request(workshop_user_id, data):
    target_phone = (data.get('targetPhone') or '').strip()
    vehicle_id = (data.get('vehicleId') or '').strip()
    permissions = data.get('permissions') or ['health', 'diagnostics', 'maintenance']
    if not target_phone and not vehicle_id:
        return None, 'Enter the owner or driver phone, or a vehicle ID'
    with get_connection() as conn:
        workshop = conn.execute(
            'SELECT id FROM workshops WHERE owner_user_id = ? ORDER BY created_at LIMIT 1',
            (workshop_user_id,)
        ).fetchone()
        target = conn.execute(
            'SELECT id FROM users WHERE phone = ?', (target_phone,)
        ).fetchone() if target_phone else None
        if target and not vehicle_id:
            vehicle = conn.execute(
                '''SELECT v.id, v.user_id FROM vehicles v
                   WHERE v.user_id = ?
                   UNION
                   SELECT v.id, v.user_id FROM vehicles v
                   JOIN vehicle_drivers vd ON vd.vehicle_id = v.id
                   WHERE vd.driver_user_id = ? AND vd.status = 'active'
                   LIMIT 1''',
                (target['id'], target['id'])
            ).fetchone()
        else:
            vehicle = conn.execute(
                'SELECT id, user_id FROM vehicles WHERE id = ?', (vehicle_id,)
            ).fetchone()
        if not workshop:
            return None, 'Workshop profile not found'
        if not vehicle:
            return None, 'Phone must belong to an owner or assigned driver, or vehicle was not found'
        request_id = f'access-{uuid.uuid4().hex[:8]}'
        conn.execute(
            '''INSERT INTO workshop_access_requests
               (id, workshop_id, owner_user_id, vehicle_id, status, permissions, expires_at)
               VALUES (?, ?, ?, ?, 'pending', ?, ?)''',
            (request_id, workshop['id'], vehicle['user_id'], vehicle_id,
             json.dumps(permissions), data.get('expiresAt'))
        )
        conn.commit()
    return request_id, None


def fetch_vehicle_drivers(owner_user_id, vehicle_id=None):
    query = '''
        SELECT vd.id, vd.vehicle_id AS vehicleId, v.name AS vehicleName,
               u.id AS driverId, u.name AS driverName, u.email,
               vd.can_request_help AS canRequestHelp,
               vd.can_share_data AS canShareData, vd.status
        FROM vehicle_drivers vd
        JOIN vehicles v ON v.id = vd.vehicle_id
        JOIN users u ON u.id = vd.driver_user_id
        WHERE vd.owner_user_id = ?
    '''
    params = [owner_user_id]
    if vehicle_id:
        query += ' AND vd.vehicle_id = ?'
        params.append(vehicle_id)
    query += ' ORDER BY vd.created_at DESC'
    with get_connection() as conn:
        rows = conn.execute(query, params).fetchall()
    return [dict(row) for row in rows]


def assign_vehicle_driver(owner_user_id, data):
    vehicle_id = resolve_user_vehicle_id(owner_user_id, data.get('vehicleId'))
    identifier = (data.get('phone') or data.get('email') or '').strip()
    if not identifier:
        return None, 'Driver phone is required'
    with get_connection() as conn:
        vehicle = conn.execute(
            'SELECT id FROM vehicles WHERE id = ? AND user_id = ?',
            (vehicle_id, owner_user_id)
        ).fetchone()
        driver = conn.execute(
            'SELECT id FROM users WHERE phone = ? OR lower(email) = lower(?)',
            (identifier, identifier)
        ).fetchone()
        if not vehicle:
            return None, 'Vehicle not found'
        if not driver:
            return None, 'Driver must create an account first'
        assignment_id = f'driver-{uuid.uuid4().hex[:8]}'
        conn.execute(
            '''DELETE FROM vehicle_drivers
               WHERE owner_user_id = ? AND vehicle_id = ? AND driver_user_id = ?''',
            (owner_user_id, vehicle_id, driver['id'])
        )
        conn.execute(
            '''INSERT INTO vehicle_drivers
               (id, owner_user_id, vehicle_id, driver_user_id, can_request_help, can_share_data)
               VALUES (?, ?, ?, ?, ?, ?)''',
            (
                assignment_id, owner_user_id, vehicle_id, driver['id'],
                1 if data.get('canRequestHelp', True) else 0,
                1 if data.get('canShareData', False) else 0
            )
        )
        conn.commit()
    return assignment_id, None


def calculate_health(vehicle_id='veh-001'):
    trips = fetch_trips(vehicle_id)
    maintenance = fetch_maintenance(vehicle_id)
    diagnostics = fetch_diagnostics(vehicle_id)

    if trips:
        avg_fuel = sum(item['average'] for item in trips) / len(trips)
    else:
        avg_fuel = 8.5

    baseline = 7.9
    trend_delta = round(((avg_fuel - baseline) / baseline) * 100, 1)

    warning_count = sum(1 for item in maintenance if item['urgency'] == 'warning')
    high_diag_count = sum(1 for item in diagnostics if item['severity'] == 'high')
    medium_diag_count = sum(1 for item in diagnostics if item['severity'] == 'medium')

    score = 100
    score -= min(abs(trend_delta) * 4.5, 30)
    score -= warning_count * 10
    score -= high_diag_count * 12
    score -= medium_diag_count * 5
    score = max(0, min(100, int(score)))

    if trend_delta > 8 or warning_count > 0 or high_diag_count > 0:
        summary = 'هناك مؤشرات على تراجع كفاءة الوقود أو احتياج فحص مبكر. يُنصح بمراجعة الحالة العامة للسيارة.'
    elif score >= 80:
        summary = 'السيارة في حالة جيدة، مع مؤشر بسيط لارتفاع استهلاك الوقود مقارنة بالمتوسط الطبيعي.'
    else:
        summary = 'حالة السيارة مستقرة بشكل نسبي، مع بعض المؤشرات التي تحتاج متابعة.'

    return {
        'fuelEfficiency': round(avg_fuel, 1),
        'baseline': baseline,
        'trend': f'{trend_delta:+.1f}%',
        'engine': {
            'avgRpm': 1950,
            'idleTime': '12%',
            'status': 'stable' if score >= 70 else 'needs_attention'
        },
        'acceleration': {'score': max(50, min(100, score + 5)), 'avg': 'مستقر' if score >= 70 else 'بحاجة لمراجعة'},
        'battery': {'voltage': 13.9, 'status': 'good' if score >= 70 else 'monitor'},
        'summary': summary,
        'score': score,
    }


def fetch_health(vehicle_id='veh-001'):
    with get_connection() as conn:
        row = conn.execute(
            '''
            SELECT score, fuel_efficiency_l_100km AS fuelEfficiency,
                   baseline_l_100km AS baseline, trend_percent AS trend,
                   engine_avg_rpm AS avgRpm, idle_time_percent AS idleTime,
                   summary
            FROM health_scores WHERE vehicle_id = ? ORDER BY created_at DESC LIMIT 1
            ''', (vehicle_id,)
        ).fetchone()
    payload = calculate_health(vehicle_id)
    payload['score'] = payload['score']
    return payload


def fetch_retention_summary(vehicle_id='veh-001'):
    with get_connection() as conn:
        raw_samples = conn.execute(
            'SELECT COUNT(*) FROM trips WHERE vehicle_id = ?', (vehicle_id,)
        ).fetchone()[0]
        alert_count = conn.execute(
            'SELECT COUNT(*) FROM alerts WHERE vehicle_id = ?', (vehicle_id,)
        ).fetchone()[0]

    health = calculate_health(vehicle_id)
    return {
        'policy': 'تخزين ملخصات الرحلات اليوميّة + قيم حرجة فقط + أرشيف دوّري 30 يومًا',
        'rawSamplesKept': max(120, raw_samples * 3),
        'summaryRecords': max(30, alert_count + raw_samples),
        'archiveDays': 30,
        'deduplication': True,
        'thresholdBased': True,
        'healthSignal': {
            'fuelEfficiency': health.get('fuelEfficiency', 8.5),
            'baseline': 7.9,
            'trend': health.get('trend', '+0.0%'),
            'score': health.get('score', 82)
        }
    }


def resolve_user_vehicle_id(user_id, requested_vehicle_id=None):
    vehicles = fetch_user_vehicles(user_id)
    owned_ids = [vehicle['id'] for vehicle in vehicles]
    with get_connection() as conn:
        assigned_rows = conn.execute(
            '''SELECT vehicle_id FROM vehicle_drivers
               WHERE driver_user_id = ? AND status = 'active' ''',
            (user_id,)
        ).fetchall()
    accessible_ids = owned_ids + [row['vehicle_id'] for row in assigned_rows]
    if requested_vehicle_id and requested_vehicle_id in accessible_ids:
        return requested_vehicle_id
    return accessible_ids[0] if accessible_ids else None


def build_dashboard(vehicle_id='veh-001'):
    vehicle = fetch_vehicle(vehicle_id)
    health = calculate_health(vehicle_id)
    summary = {
        'kmToday': 178,
        'fuelUsedToday': 16.4,
        'serviceDue': 'في 380 كم',
        'alertsCount': len(fetch_alerts(vehicle_id)),
        'score': health.get('score', vehicle.get('healthScore', 0))
    }
    retention = fetch_retention_summary(vehicle_id)
    insights = [
        'استهلاك الوقود الحالي أعلى من المتوسط بمقدار 8% خلال آخر 30 يومًا.',
        'تأثير التسارع على استهلاك الوقود متزايد، لذلك يُنصح بمراجعة نظام الوقود.',
        'تم حفظ ملخصات الرحلات فقط عندما تختلف القيم عن الحدّ الأدنى المحدد، لتقليل تكلفة التخزين.'
    ]
    return {'vehicle': vehicle, 'summary': summary, 'retention': retention, 'insights': insights}


API_DATA = {}


def refresh_api_data():
    global API_DATA
    API_DATA = {
        '/api/dashboard': build_dashboard(),
        '/api/trips': fetch_trips(),
        '/api/maintenance': fetch_maintenance(),
        '/api/diagnostics': fetch_diagnostics(),
        '/api/alerts': fetch_alerts(),
        '/api/health': fetch_health(),
        '/api/vehicle': fetch_vehicle(),
        '/api/profile': fetch_vehicle(),
        '/api/settings': {
            'maintenance': True,
            'engineAlerts': True,
            'backup': 'كل 7 أيام',
            'units': 'km / L / SAR'
        },
        '/api/garage/vehicles': fetch_garage_vehicles(),
        '/api/garage/reports': fetch_garage_reports(),
    }


class Handler(SimpleHTTPRequestHandler):
    def _send_json(self, status_code, payload):
        body = json.dumps(payload, ensure_ascii=False, indent=2).encode('utf-8')
        self.send_response(status_code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length <= 0 or length > MAX_JSON_BYTES:
                return {}
            raw = self.rfile.read(length)
            if not raw:
                return {}
            return json.loads(raw.decode('utf-8'))
        except Exception:
            return {}

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)
        requested_vehicle_id = query.get('vehicleId', [None])[0]

        protected_vehicle_routes = {
            '/api/dashboard': build_dashboard,
            '/api/vehicle': fetch_vehicle,
            '/api/profile': fetch_vehicle,
            '/api/trips': fetch_trips,
            '/api/maintenance': fetch_maintenance,
            '/api/diagnostics': fetch_diagnostics,
            '/api/alerts': fetch_alerts,
            '/api/health': fetch_health,
        }
        if path in protected_vehicle_routes:
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            selected_vehicle_id = resolve_user_vehicle_id(user_id, requested_vehicle_id)
            if not selected_vehicle_id:
                self._send_json(403, {'error': 'No accessible vehicle'})
                return
            self._send_json(200, protected_vehicle_routes[path](selected_vehicle_id))
            return

        if path == '/api/settings':
            if not require_auth(self):
                self._send_json(401, {'error': 'Unauthorized'})
                return
            self._send_json(200, {
                'maintenance': True,
                'engineAlerts': True,
                'backup': 'every 7 days',
                'units': 'km / L'
            })
            return

        if path in ('/api/garage/vehicles', '/api/garage/reports'):
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            if 'workshop' not in fetch_user_roles(user_id):
                self._send_json(403, {'error': 'Workshop role required'})
                return
            payload = fetch_garage_vehicles(user_id) if path.endswith('vehicles') else fetch_garage_reports(user_id)
            self._send_json(200, payload)
            return

        if path == '/api/dashboard':
            user_id = require_auth(self)
            if user_id:
                selected_vehicle_id = resolve_user_vehicle_id(user_id, requested_vehicle_id)
                self._send_json(200, build_dashboard(selected_vehicle_id))
                return
            if path in API_DATA:
                self._send_json(200, API_DATA[path])
                return

        if path == '/api/vehicle':
            user_id = require_auth(self)
            if user_id:
                selected_vehicle_id = resolve_user_vehicle_id(user_id, requested_vehicle_id)
                self._send_json(200, fetch_vehicle(selected_vehicle_id))
                return
            if path in API_DATA:
                self._send_json(200, API_DATA[path])
                return

        if path == '/api/trips':
            user_id = require_auth(self)
            if user_id:
                selected_vehicle_id = resolve_user_vehicle_id(user_id, requested_vehicle_id)
                self._send_json(200, fetch_trips(selected_vehicle_id))
                return
            if path in API_DATA:
                self._send_json(200, API_DATA[path])
                return

        if path == '/api/maintenance':
            user_id = require_auth(self)
            if user_id:
                selected_vehicle_id = resolve_user_vehicle_id(user_id, requested_vehicle_id)
                self._send_json(200, fetch_maintenance(selected_vehicle_id))
                return
            if path in API_DATA:
                self._send_json(200, API_DATA[path])
                return

        if path == '/api/diagnostics':
            user_id = require_auth(self)
            if user_id:
                selected_vehicle_id = resolve_user_vehicle_id(user_id, requested_vehicle_id)
                self._send_json(200, fetch_diagnostics(selected_vehicle_id))
                return
            if path in API_DATA:
                self._send_json(200, API_DATA[path])
                return

        if path == '/api/alerts':
            user_id = require_auth(self)
            if user_id:
                selected_vehicle_id = resolve_user_vehicle_id(user_id, requested_vehicle_id)
                self._send_json(200, fetch_alerts(selected_vehicle_id))
                return
            if path in API_DATA:
                self._send_json(200, API_DATA[path])
                return

        if path == '/api/health':
            user_id = require_auth(self)
            if user_id:
                selected_vehicle_id = resolve_user_vehicle_id(user_id, requested_vehicle_id)
                self._send_json(200, fetch_health(selected_vehicle_id))
                return
            if path in API_DATA:
                self._send_json(200, API_DATA[path])
                return

        if path == '/api/vehicles':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            self._send_json(200, {'vehicles': fetch_user_vehicles(user_id)})
            return

        if path == '/api/export':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            self._send_json(200, {'export': export_vehicle_data(user_id)})
            return

        if path == '/api/workshops':
            if not require_auth(self):
                self._send_json(401, {'error': 'Unauthorized'})
                return
            self._send_json(200, {'workshops': fetch_workshops()})
            return

        if path == '/api/drivers':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            self._send_json(200, {'drivers': fetch_vehicle_drivers(user_id)})
            return

        if path == '/api/access-requests':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            with get_connection() as conn:
                rows = conn.execute(
                    '''SELECT r.id, r.vehicle_id AS vehicleId, v.name AS vehicleName,
                              r.status, r.permissions, r.expires_at AS expiresAt,
                              w.name AS workshopName, w.phone AS workshopPhone
                       FROM workshop_access_requests r
                       JOIN vehicles v ON v.id = r.vehicle_id
                       JOIN workshops w ON w.id = r.workshop_id
                       WHERE r.owner_user_id = ? ORDER BY r.created_at DESC''',
                    (user_id,)
                ).fetchall()
            requests = [dict(row) for row in rows]
            for request in requests:
                request['permissions'] = json.loads(request['permissions'])
            self._send_json(200, {'requests': requests})
            return

        if path == '/api/device-access':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            vehicle_id = query.get('vehicleId', [None])[0]
            self._send_json(200, {'access': fetch_device_access(user_id, vehicle_id)})
            return

        if path == '/':
            path = '/index.html'
        if path == '/garage':
            path = '/garage.html'
        if path == '/mobile':
            path = '/mobile.html'

        file_path = os.path.normpath(os.path.join(PUBLIC_DIR, path.lstrip('/')))
        if not file_path.startswith(PUBLIC_DIR):
            self.send_error(403, 'Forbidden')
            return

        if not os.path.exists(file_path) or not os.path.isfile(file_path):
            self.send_error(404, 'Not found')
            return

        extension = os.path.splitext(file_path)[1].lower()
        content_types = {
            '.html': 'text/html; charset=utf-8',
            '.css': 'text/css; charset=utf-8',
            '.js': 'application/javascript; charset=utf-8',
            '.json': 'application/json; charset=utf-8',
            '.svg': 'image/svg+xml',
            '.png': 'image/png',
            '.jpg': 'image/jpeg',
            '.jpeg': 'image/jpeg'
        }

        with open(file_path, 'rb') as fh:
            content = fh.read()

        self.send_response(200)
        self.send_header('Content-Type', content_types.get(extension, 'application/octet-stream'))
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def do_POST(self):
        path = urlparse(self.path).path
        payload = self._read_json()

        if path == '/api/auth/signup':
            name = (payload.get('name') or '').strip()
            phone = (payload.get('phone') or '').strip()
            email = (payload.get('email') or '').strip().lower()
            password = payload.get('password') or ''
            if not name or not phone or not password:
                self._send_json(400, {'error': 'Missing required fields'})
                return
            if len(password) < 12:
                self._send_json(400, {'error': 'Password must be at least 12 characters'})
                return
            if fetch_user_by_phone(phone) or (email and fetch_user_by_email(email)):
                self._send_json(409, {'error': 'Phone or email already exists'})
                return
            workshop = payload.get('workshop') or {}
            user_id = f'user-{uuid.uuid4().hex[:8]}'
            roles = normalize_roles(payload.get('roles'))
            if 'workshop' in roles:
                required_workshop = ['name', 'phone', 'services']
                if any(not str(workshop.get(key) or '').strip() for key in required_workshop):
                    self._send_json(400, {'error': 'Workshop details are required'})
                    return
            with get_connection() as conn:
                conn.execute(
                    'INSERT INTO users (id, name, email, phone, password_hash) VALUES (?, ?, ?, ?, ?)',
                    (user_id, name, email or None, phone, hash_password(password))
                )
                conn.commit()
            save_user_roles(user_id, roles)
            if 'workshop' in roles:
                create_workshop(user_id, workshop)
            token = create_session(user_id)
            audit(user_id, 'auth.signup', 'user', user_id)
            self._send_json(201, {'token': token, 'userId': user_id, 'name': name, 'email': email, 'phone': phone, 'roles': roles})
            return

        if path == '/api/auth/login':
            phone = (payload.get('phone') or '').strip()
            email = (payload.get('email') or '').strip().lower()
            password = payload.get('password') or ''
            user = fetch_user_by_phone(phone) if phone else fetch_user_by_email(email)
            verified, must_upgrade_hash = verify_password(password, user.get('password_hash') if user else None)
            if not user or not verified:
                self._send_json(401, {'error': 'Invalid credentials'})
                return
            if must_upgrade_hash:
                with get_connection() as conn:
                    conn.execute('UPDATE users SET password_hash = ? WHERE id = ?', (hash_password(password), user['id']))
                    conn.commit()
            token = create_session(user['id'])
            roles = fetch_user_roles(user['id'])
            audit(user['id'], 'auth.login', 'user', user['id'])
            self._send_json(200, {'token': token, 'userId': user['id'], 'name': user['name'], 'email': user['email'], 'phone': user['phone'], 'roles': roles})
            return

        if path == '/api/auth/logout':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            revoke_session(self)
            audit(user_id, 'auth.logout', 'user', user_id)
            self._send_json(200, {'status': 'logged_out'})
            return

        if path == '/api/auth/change-password':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            current_password = payload.get('currentPassword') or ''
            new_password = payload.get('newPassword') or ''
            if len(new_password) < 12:
                self._send_json(400, {'error': 'Password must be at least 12 characters'})
                return
            user = fetch_user_by_id(user_id)
            verified, _ = verify_password(current_password, user.get('password_hash') if user else None)
            if not verified:
                self._send_json(401, {'error': 'Current password is incorrect'})
                return
            with get_connection() as conn:
                conn.execute('UPDATE users SET password_hash = ? WHERE id = ?', (hash_password(new_password), user_id))
                conn.execute(
                    'UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL',
                    (utc_now().isoformat(), user_id)
                )
                conn.commit()
            token = create_session(user_id)
            audit(user_id, 'auth.change_password', 'user', user_id)
            self._send_json(200, {'token': token, 'status': 'password_changed'})
            return

        if path == '/api/account/roles':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            roles, error = add_account_role(user_id, payload.get('role'), payload.get('workshop'))
            if error:
                self._send_json(400, {'error': error})
                return
            audit(user_id, 'account.add_role', 'user', user_id)
            self._send_json(200, {'roles': roles})
            return

        if path == '/api/vehicles':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            if payload.get('action') == 'create':
                try:
                    vehicle_id = create_vehicle_for_user(user_id, payload.get('vehicle', {}))
                except ValueError as error:
                    self._send_json(409, {'error': str(error)})
                    return
                self._send_json(201, {'vehicleId': vehicle_id, 'vehicles': fetch_user_vehicles(user_id)})
                return
            self._send_json(200, {'vehicles': fetch_user_vehicles(user_id)})
            return

        if path == '/api/export':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            data = export_vehicle_data(user_id)
            self._send_json(200, {'export': data, 'format': 'json'})
            return

        if path == '/api/workshops':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            if payload.get('action') == 'register':
                if 'workshop' not in fetch_user_roles(user_id):
                    self._send_json(403, {'error': 'Workshop role required'})
                    return
                required = ['name', 'services', 'phone']
                if any(not str(payload.get(key) or '').strip() for key in required):
                    self._send_json(400, {'error': 'Workshop name, services, and phone are required'})
                    return
                workshop_id = create_workshop(user_id, payload)
                self._send_json(201, {'workshopId': workshop_id})
                return
            if payload.get('action') == 'request-access':
                request_id = create_access_request(user_id, payload)
                if not request_id:
                    self._send_json(404, {'error': 'Workshop not found'})
                    return
                self._send_json(201, {'requestId': request_id, 'status': 'pending'})
                return
            if payload.get('action') == 'workshop-request-access':
                if 'workshop' not in fetch_user_roles(user_id):
                    self._send_json(403, {'error': 'Workshop role required'})
                    return
                request_id, error = create_workshop_access_request(user_id, payload)
                if error:
                    self._send_json(400, {'error': error})
                    return
                self._send_json(201, {'requestId': request_id, 'status': 'pending'})
                return
            if payload.get('action') == 'resolve-access-request':
                status = payload.get('status')
                if status not in ('approved', 'rejected'):
                    self._send_json(400, {'error': 'Invalid request status'})
                    return
                with get_connection() as conn:
                    request = conn.execute(
                        '''SELECT id FROM workshop_access_requests
                           WHERE id = ? AND owner_user_id = ? AND status = 'pending' ''',
                        (payload.get('requestId'), user_id)
                    ).fetchone()
                    if not request:
                        self._send_json(403, {'error': 'Only the vehicle owner can resolve this request'})
                        return
                    permissions = payload.get('permissions') or ['health', 'diagnostics', 'maintenance']
                    updated = conn.execute(
                        '''UPDATE workshop_access_requests SET status = ?
                           , permissions = ? WHERE id = ? AND owner_user_id = ?''',
                        (status, json.dumps(permissions), payload.get('requestId'), user_id)
                    ).rowcount
                    conn.commit()
                self._send_json(200, {'status': status})
                return
            self._send_json(400, {'error': 'Unsupported workshop action'})
            return

        if path == '/api/drivers':
            owner_user_id = require_auth(self)
            if not owner_user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            if payload.get('action') != 'assign':
                self._send_json(400, {'error': 'Unsupported driver action'})
                return
            assignment_id, error = assign_vehicle_driver(owner_user_id, payload)
            if error:
                self._send_json(400, {'error': error})
                return
            self._send_json(201, {'assignmentId': assignment_id, 'drivers': fetch_vehicle_drivers(owner_user_id)})
            return

        if path == '/api/device-access':
            user_id = require_auth(self)
            if not user_id:
                self._send_json(401, {'error': 'Unauthorized'})
                return
            action = payload.get('action')
            if action == 'grant':
                access_id, error = grant_device_access(user_id, payload)
                if error:
                    self._send_json(403 if 'owner' in error.lower() else 400, {'error': error})
                    return
                self._send_json(201, {'accessId': access_id, 'access': fetch_device_access(user_id, payload.get('vehicleId'))})
                return
            if action == 'revoke':
                if not revoke_device_access(user_id, payload.get('accessId')):
                    self._send_json(403, {'error': 'Only the owner can revoke this access'})
                    return
                self._send_json(200, {'access': fetch_device_access(user_id, payload.get('vehicleId'))})
                return
            if action == 'check':
                vehicle_id = payload.get('vehicleId')
                allowed = driver_has_device_access(user_id, vehicle_id)
                self._send_json(200, {'allowed': allowed, 'vehicleId': vehicle_id})
                return
            self._send_json(400, {'error': 'Unsupported device access action'})
            return

        self._send_json(404, {'error': 'Not found'})

    def log_message(self, format, *args):
        return


if __name__ == '__main__':
    init_db()
    if HOST not in ('127.0.0.1', '::1') and os.environ.get('DRIVECARE_ALLOW_LAN') != '1':
        raise RuntimeError('Refusing non-local bind. Set DRIVECARE_ALLOW_LAN=1 only after a security review.')
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f'DriveCare running at http://{HOST}:{PORT}')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\nServer stopped.')
        server.server_close()
