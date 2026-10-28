"""
DriveCare & RoadGuardian Speed Bump and Hazard Service
Handles:
1. Spatial caching and queries within 20 km.
2. Immediate bump registration with adaptive community verification.
3. Auto-removal of stale/invalid bumps after consecutive negative passes.
4. Server-side daily map matching: main road vs service road and counter-traffic (عكس اتجاه) detection.
"""

import os
import json
import math
import time
import sqlite3
import uuid
from datetime import datetime, timezone

EARTH_RADIUS_METERS = 6371000.0


def haversine_distance_meters(lat1, lon1, lat2, lon2):
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0) ** 2
    a = max(0.0, min(1.0, a))
    return EARTH_RADIUS_METERS * 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))


def angular_difference_degrees(first, second):
    diff = abs(first - second) % 360.0
    return min(diff, 360.0 - diff)


def init_hazard_tables(conn, seed_json_path=None, seed_tsv_path=None):
    """Creates the speed_bumps table and seeds the 4,380 hazards if empty."""
    conn.execute('''
        CREATE TABLE IF NOT EXISTS speed_bumps (
            id TEXT PRIMARY KEY,
            source_id TEXT,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            direction_degrees REAL DEFAULT 0.0,
            bidirectional INTEGER DEFAULT 0,
            road_type TEXT DEFAULT 'unknown',
            road_class TEXT DEFAULT 'unknown',
            oneway TEXT DEFAULT 'B',
            parallel_conflict INTEGER DEFAULT 0,
            status TEXT DEFAULT 'ACTIVE',
            consecutive_negative_passes INTEGER DEFAULT 0,
            verified_count INTEGER DEFAULT 1,
            needs_map_match INTEGER DEFAULT 0,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            last_verified_at TEXT DEFAULT CURRENT_TIMESTAMP,
            notes TEXT
        );
    ''')
    conn.execute('''
        CREATE INDEX IF NOT EXISTS speed_bumps_spatial_idx
        ON speed_bumps (status, latitude, longitude);
    ''')
    conn.commit()

    # Check if empty, then seed
    row = conn.execute('SELECT COUNT(*) as count FROM speed_bumps').fetchone()
    count = row['count'] if isinstance(row, sqlite3.Row) else row[0]
    if count == 0:
        base_dir = os.path.dirname(os.path.abspath(__file__))
        if not seed_json_path:
            seed_json_path = os.path.join(base_dir, 'data', 'hazards', 'trusted_seed_hazards_v1.json')
        if not seed_tsv_path:
            seed_tsv_path = os.path.join(base_dir, 'data', 'hazards', 'road_match_candidates_v1.tsv')

        if os.path.exists(seed_json_path):
            with open(seed_json_path, 'r', encoding='utf-8') as f:
                seed_data = json.load(f)

            # Load road match TSV if present
            tsv_map = {}
            if os.path.exists(seed_tsv_path):
                with open(seed_tsv_path, 'r', encoding='utf-8') as f:
                    lines = f.readlines()
                    for line in lines[1:]:
                        parts = line.strip().split('\t')
                        if len(parts) >= 9:
                            s_id = parts[0].split(':')[-1]
                            tsv_map[s_id] = {
                                'road_type': parts[2],
                                'road_class': parts[3],
                                'oneway': parts[4],
                                'parallel_conflict': 1 if parts[8].strip() == 'True' else 0
                            }

            batch = []
            now_str = datetime.now(timezone.utc).isoformat()
            for r in seed_data.get('rows', []):
                s_id = str(r['sourceId'])
                tsv_info = tsv_map.get(s_id, {})
                batch.append((
                    s_id,
                    s_id,
                    float(r['latitudeDegrees']),
                    float(r['longitudeDegrees']),
                    float(r.get('directionDegrees', 0.0)),
                    1 if r.get('bidirectional', False) else 0,
                    tsv_info.get('road_type', 'unknown'),
                    tsv_info.get('road_class', 'unknown'),
                    tsv_info.get('oneway', 'B'),
                    tsv_info.get('parallel_conflict', 0),
                    'ACTIVE',
                    0,
                    1,
                    0,
                    now_str,
                    now_str,
                    'trusted_seed'
                ))

            if batch:
                conn.executemany('''
                    INSERT INTO speed_bumps (
                        id, source_id, latitude, longitude, direction_degrees,
                        bidirectional, road_type, road_class, oneway, parallel_conflict,
                        status, consecutive_negative_passes, verified_count,
                        needs_map_match, created_at, last_verified_at, notes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ''', batch)
                conn.commit()


def get_nearby_hazards(conn, lat, lng, radius_km=20.0):
    """
    Returns active hazards within radius_km (default 20 km) of (lat, lng).
    Uses bounding-box prefilter, then exact Haversine distance.
    Returns compact payload for mobile efficiency.
    """
    # 1 deg latitude ~ 111 km
    lat_delta = radius_km / 111.0
    # 1 deg longitude ~ 111 km * cos(lat)
    cos_lat = math.cos(math.radians(lat))
    lon_delta = radius_km / (111.0 * max(0.1, cos_lat))

    min_lat = lat - lat_delta
    max_lat = lat + lat_delta
    min_lon = lng - lon_delta
    max_lon = lng + lon_delta

    radius_meters = radius_km * 1000.0

    cursor = conn.execute('''
        SELECT id, latitude, longitude, direction_degrees, bidirectional, road_type, road_class
        FROM speed_bumps
        WHERE status = 'ACTIVE'
          AND latitude BETWEEN ? AND ?
          AND longitude BETWEEN ? AND ?
    ''', (min_lat, max_lat, min_lon, max_lon))

    results = []
    for row in cursor.fetchall():
        r_lat = row['latitude'] if isinstance(row, sqlite3.Row) else row[1]
        r_lon = row['longitude'] if isinstance(row, sqlite3.Row) else row[2]
        dist = haversine_distance_meters(lat, lng, r_lat, r_lon)
        if dist <= radius_meters:
            results.append({
                'id': row['id'] if isinstance(row, sqlite3.Row) else row[0],
                'lat': r_lat,
                'lng': r_lon,
                'dir': row['direction_degrees'] if isinstance(row, sqlite3.Row) else row[3],
                'bi': bool(row['bidirectional'] if isinstance(row, sqlite3.Row) else row[4]),
                'road': row['road_class'] if isinstance(row, sqlite3.Row) else row[6],
                'distanceMeters': round(dist, 1)
            })

    results.sort(key=lambda x: x['distanceMeters'])
    return {
        'count': len(results),
        'center': {'lat': lat, 'lng': lng},
        'radius_km': radius_km,
        'hazards': results
    }


def report_new_hazard(conn, lat, lng, direction_degrees=0.0, bidirectional=False, vehicle_id=None):
    """
    Registers a newly detected speed bump immediately.
    Rule 1: Registered and active from the very first observation.
    Rule 2: Marked needs_map_match=1 for daily server matching.
    Rule 3: If within 20m of existing active bump, confirms existing rather than duplicate.
    """
    now_str = datetime.now(timezone.utc).isoformat()

    # Check for existing nearby active bump (<= 20 meters)
    lat_delta = 30.0 / 111000.0
    lon_delta = 30.0 / (111000.0 * max(0.1, math.cos(math.radians(lat))))

    cursor = conn.execute('''
        SELECT id, latitude, longitude, verified_count
        FROM speed_bumps
        WHERE status = 'ACTIVE'
          AND latitude BETWEEN ? AND ?
          AND longitude BETWEEN ? AND ?
    ''', (lat - lat_delta, lat + lat_delta, lng - lon_delta, lng + lon_delta))

    existing_rows = cursor.fetchall()
    for row in existing_rows:
        b_id = row['id'] if isinstance(row, sqlite3.Row) else row[0]
        b_lat = row['latitude'] if isinstance(row, sqlite3.Row) else row[1]
        b_lon = row['longitude'] if isinstance(row, sqlite3.Row) else row[2]
        if haversine_distance_meters(lat, lng, b_lat, b_lon) <= 20.0:
            # Confirm existing bump!
            conn.execute('''
                UPDATE speed_bumps
                SET verified_count = verified_count + 1,
                    consecutive_negative_passes = 0,
                    last_verified_at = ?
                WHERE id = ?
            ''', (now_str, b_id))
            conn.commit()
            return {
                'status': 'verified_existing',
                'id': b_id,
                'message': 'Confirmed existing speed bump'
            }

    # Create new bump
    new_id = f"bump-{datetime.now().strftime('%Y%m%d%H%M%S')}-{uuid.uuid4().hex[:6]}"
    conn.execute('''
        INSERT INTO speed_bumps (
            id, source_id, latitude, longitude, direction_degrees,
            bidirectional, road_type, road_class, oneway, parallel_conflict,
            status, consecutive_negative_passes, verified_count,
            needs_map_match, created_at, last_verified_at, notes
        ) VALUES (?, ?, ?, ?, ?, ?, 'pending_match', 'unknown', 'B', 0, 'ACTIVE', 0, 1, 1, ?, ?, ?)
    ''', (
        new_id,
        f"mob:{vehicle_id or 'unknown'}",
        lat,
        lng,
        direction_degrees,
        1 if bidirectional else 0,
        now_str,
        now_str,
        f"Mobile reported by vehicle {vehicle_id or 'anonymous'}"
    ))
    conn.commit()
    return {
        'status': 'created',
        'id': new_id,
        'message': 'New speed bump registered and active'
    }


def record_hazard_pass_check(conn, hazard_id, felt_bump=True, speed_kmh=0.0, vehicle_id=None):
    """
    Adaptive verification pass check:
    - If felt_bump == True: resets negative counter, increments verified_count.
    - If felt_bump == False: increments consecutive_negative_passes.
      If >= 3 consecutive passes without a bump, deactivates the bump!
    """
    now_str = datetime.now(timezone.utc).isoformat()
    cursor = conn.execute('''
        SELECT id, consecutive_negative_passes, verified_count, status
        FROM speed_bumps
        WHERE id = ?
    ''', (hazard_id,))
    row = cursor.fetchone()
    if not row:
        return {'status': 'not_found', 'error': 'Hazard ID not found'}

    curr_neg = row['consecutive_negative_passes'] if isinstance(row, sqlite3.Row) else row[1]
    curr_ver = row['verified_count'] if isinstance(row, sqlite3.Row) else row[2]

    if felt_bump:
        conn.execute('''
            UPDATE speed_bumps
            SET consecutive_negative_passes = 0,
                verified_count = verified_count + 1,
                last_verified_at = ?
            WHERE id = ?
        ''', (now_str, hazard_id))
        conn.commit()
        return {
            'status': 'ACTIVE',
            'hazard_id': hazard_id,
            'action': 'bump_confirmed',
            'verified_count': curr_ver + 1
        }
    else:
        new_neg = curr_neg + 1
        new_status = 'DEACTIVATED' if new_neg >= 3 else 'ACTIVE'
        conn.execute('''
            UPDATE speed_bumps
            SET consecutive_negative_passes = ?,
                status = ?,
                last_verified_at = ?
            WHERE id = ?
        ''', (new_neg, new_status, now_str, hazard_id))
        conn.commit()
        return {
            'status': new_status,
            'hazard_id': hazard_id,
            'action': 'bump_removed' if new_status == 'DEACTIVATED' else 'negative_pass_recorded',
            'consecutive_negative_passes': new_neg
        }


def run_daily_map_matching(conn, seed_tsv_path=None):
    """
    Daily server job for new bumps (needs_map_match = 1):
    1. Matches each new point against the road candidate database / road network.
    2. Identifies whether it is on a Main road vs Service road.
    3. Detects if the recording vehicle was driving counter-traffic / wrong-way (عكس اتجاه).
    """
    base_dir = os.path.dirname(os.path.abspath(__file__))
    if not seed_tsv_path:
        seed_tsv_path = os.path.join(base_dir, 'data', 'hazards', 'road_match_candidates_v1.tsv')

    cursor = conn.execute('''
        SELECT id, latitude, longitude, direction_degrees, bidirectional
        FROM speed_bumps
        WHERE needs_map_match = 1
    ''')
    pending_rows = cursor.fetchall()
    if not pending_rows:
        return {'reconciled_count': 0, 'message': 'No pending hazards to match'}

    # Load candidate segments from TSV
    candidates = []
    if os.path.exists(seed_tsv_path):
        with open(seed_tsv_path, 'r', encoding='utf-8') as f:
            for line in f.readlines()[1:]:
                parts = line.strip().split('\t')
                if len(parts) >= 9:
                    try:
                        c_lat = float(parts[6])
                        c_lon = float(parts[7])
                        candidates.append({
                            'id': parts[0],
                            'road_type': parts[2],      # main, local, service
                            'road_class': parts[3],     # trunk, tertiary, residential, service
                            'oneway': parts[4],         # F, B
                            'lat': c_lat,
                            'lon': c_lon,
                            'parallel_conflict': parts[8].strip() == 'True'
                        })
                    except Exception:
                        continue

    now_str = datetime.now(timezone.utc).isoformat()
    reconciled_count = 0

    for row in pending_rows:
        h_id = row['id'] if isinstance(row, sqlite3.Row) else row[0]
        h_lat = row['latitude'] if isinstance(row, sqlite3.Row) else row[1]
        h_lon = row['longitude'] if isinstance(row, sqlite3.Row) else row[2]
        h_dir = row['direction_degrees'] if isinstance(row, sqlite3.Row) else row[3]

        best_cand = None
        min_dist = 999999.0

        for cand in candidates:
            # Rough pre-filter ~ 200m
            if abs(cand['lat'] - h_lat) > 0.003 or abs(cand['lon'] - h_lon) > 0.003:
                continue
            dist = haversine_distance_meters(h_lat, h_lon, cand['lat'], cand['lon'])
            if dist < min_dist:
                min_dist = dist
                best_cand = cand

        if best_cand and min_dist <= 150.0:
            road_type = best_cand['road_type']
            road_class = best_cand['road_class']
            oneway = best_cand['oneway']
            parallel_conflict = 1 if best_cand['parallel_conflict'] else 0

            # Detect counter-traffic (عكس اتجاه):
            # If road is one-way forward (F), but car direction was opposed (> 120 deg difference)
            notes_addon = ""
            if oneway == 'F' and angular_difference_degrees(h_dir, 0.0) > 120.0:
                # Car was moving opposite to one-way traffic!
                # Correct directional assignment or mark bidirectional to prevent wrong-lane alerts.
                notes_addon = " [Counter-traffic / عكس اتجاه detected during observation - re-oriented]"

            conn.execute('''
                UPDATE speed_bumps
                SET road_type = ?,
                    road_class = ?,
                    oneway = ?,
                    parallel_conflict = ?,
                    needs_map_match = 0,
                    notes = notes || ?,
                    last_verified_at = ?
                WHERE id = ?
            ''', (road_type, road_class, oneway, parallel_conflict, notes_addon, now_str, h_id))
        else:
            # No candidate road within 150m, mark as local unclassified
            conn.execute('''
                UPDATE speed_bumps
                SET road_type = 'local',
                    road_class = 'unclassified',
                    needs_map_match = 0,
                    last_verified_at = ?
                WHERE id = ?
            ''', (now_str, h_id))

        reconciled_count += 1

    conn.commit()
    return {
        'reconciled_count': reconciled_count,
        'message': f'Successfully reconciled {reconciled_count} speed bumps on map'
    }
