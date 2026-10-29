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
                if r.get('type') == 'UNKNOWN_ROAD_ANOMALY':
                    continue
                s_id = str(r['sourceId'])
                tsv_info = tsv_map.get(s_id, {})
                is_bi = bool(r.get('bidirectional', False))
                default_oneway = 'B' if is_bi else 'F'
                batch.append((
                    s_id,
                    s_id,
                    float(r['latitudeDegrees']),
                    float(r['longitudeDegrees']),
                    float(r.get('directionDegrees', 0.0)),
                    1 if is_bi else 0,
                    tsv_info.get('road_type', 'unknown'),
                    tsv_info.get('road_class', 'unknown'),
                    tsv_info.get('oneway', default_oneway),
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


def initial_bearing_degrees(lat1, lon1, lat2, lon2):
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dlambda = math.radians(lon2 - lon1)
    y = math.sin(dlambda) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(dlambda)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def report_new_hazard(conn, lat, lng, direction_degrees=0.0, bidirectional=False, vehicle_id=None):
    """
    Registers a newly detected speed bump immediately (or re-activates a deactivated bump).
    Rule 1: Registered and active from the very first observation (speed <= 21 km/h + bump pattern).
    Rule 2: Marked needs_map_match=1 for daily server road-map matching.
    Rule 3: If within 22m of an existing bump (ACTIVE or DEACTIVATED after 3 negative passes)
            in the same lane direction, confirms/re-activates existing rather than duplicating.
    """
    now_str = datetime.now(timezone.utc).isoformat()

    # Check for existing nearby bump (ACTIVE or DEACTIVATED <= 22 meters)
    lat_delta = 35.0 / 111000.0
    lon_delta = 35.0 / (111000.0 * max(0.1, math.cos(math.radians(lat))))

    cursor = conn.execute('''
        SELECT id, latitude, longitude, direction_degrees, bidirectional, status, verified_count
        FROM speed_bumps
        WHERE status IN ('ACTIVE', 'DEACTIVATED')
          AND latitude BETWEEN ? AND ?
          AND longitude BETWEEN ? AND ?
    ''', (lat - lat_delta, lat + lat_delta, lng - lon_delta, lng + lon_delta))

    existing_rows = cursor.fetchall()
    for row in existing_rows:
        b_id = row['id'] if isinstance(row, sqlite3.Row) else row[0]
        b_lat = row['latitude'] if isinstance(row, sqlite3.Row) else row[1]
        b_lon = row['longitude'] if isinstance(row, sqlite3.Row) else row[2]
        b_dir = float((row['direction_degrees'] if isinstance(row, sqlite3.Row) else row[3]) or 0.0)
        b_bi = bool((row['bidirectional'] if isinstance(row, sqlite3.Row) else row[4]) or 0)
        b_status = row['status'] if isinstance(row, sqlite3.Row) else row[5]

        if haversine_distance_meters(lat, lng, b_lat, b_lon) <= 22.0:
            same_dir = b_bi or bidirectional or (angular_difference_degrees(direction_degrees, b_dir) <= 55.0)
            if same_dir:
                # Confirm or RE-ACTIVATE existing bump!
                conn.execute('''
                    UPDATE speed_bumps
                    SET status = 'ACTIVE',
                        verified_count = verified_count + 1,
                        consecutive_negative_passes = 0,
                        last_verified_at = ?
                    WHERE id = ?
                ''', (now_str, b_id))
                conn.commit()
                action_status = 'reactivated_existing' if b_status == 'DEACTIVATED' else 'verified_existing'
                return {
                    'status': action_status,
                    'id': b_id,
                    'message': 'Re-activated speed bump after pattern returned' if b_status == 'DEACTIVATED' else 'Confirmed existing speed bump'
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


def _query_osm_road_segment(lat, lon):
    """
    Queries OpenStreetMap Overpass API (with short timeout) for the nearest road way
    within 25m of (lat, lon) to obtain true road segment bearing and oneway status.
    Returns dict {'road_type', 'road_class', 'oneway', 'bearing'} or None if offline.
    """
    try:
        import urllib.request
        import urllib.parse
        query = f'[out:json][timeout:3];way(around:25,{lat},{lon})["highway"];out geom 1;'
        url = 'https://overpass-api.de/api/interpreter?data=' + urllib.parse.quote(query)
        req = urllib.request.Request(url, headers={'User-Agent': 'DriveCare-RoadGuardian/1.0'})
        with urllib.request.urlopen(req, timeout=3.5) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            elements = data.get('elements') or []
            if not elements:
                return None
            way = elements[0]
            tags = way.get('tags') or {}
            hw = tags.get('highway', 'unclassified')
            oneway_raw = str(tags.get('oneway', 'no')).lower()
            geom = way.get('geometry') or []
            if len(geom) < 2:
                return None
            # Find closest segment on the way
            best_bearing = 0.0
            best_seg_dist = 999999.0
            for i in range(len(geom) - 1):
                p1, p2 = geom[i], geom[i + 1]
                mid_lat = (p1['lat'] + p2['lat']) / 2.0
                mid_lon = (p1['lon'] + p2['lon']) / 2.0
                d = haversine_distance_meters(lat, lon, mid_lat, mid_lon)
                if d < best_seg_dist:
                    best_seg_dist = d
                    best_bearing = initial_bearing_degrees(p1['lat'], p1['lon'], p2['lat'], p2['lon'])
            if oneway_raw in ('-1', 'reverse'):
                best_bearing = (best_bearing + 180.0) % 360.0
                oneway_code = 'F'
            elif oneway_raw in ('yes', '1', 'true'):
                oneway_code = 'F'
            else:
                oneway_code = 'B'
            r_type = 'service' if 'service' in hw else ('main' if hw in ('motorway', 'trunk', 'primary', 'secondary', 'tertiary') else 'local')
            return {
                'road_type': r_type,
                'road_class': hw,
                'oneway': oneway_code,
                'bearing': best_bearing,
                'parallel_conflict': False
            }
    except Exception:
        return None


def run_daily_map_matching(conn, seed_tsv_path=None, seed_json_path=None):
    """
    Daily server job for ONLY newly recorded bumps (needs_map_match = 1):
    1. Matches each new point against the road network (local road candidates + OSM Overpass geometry).
    2. Identifies whether it is on a Main road vs Service road.
    3. Detects if the recording vehicle was driving counter-traffic / wrong-way (عكس اتجاه)
       (e.g., driving Southbound inside a Northbound one-way lane) and automatically corrects
       direction_degrees to the true legal direction of that lane (or merges with an existing bump
       on that legal lane) so opposing traffic never receives a false speed bump alert!
    """
    base_dir = os.path.dirname(os.path.abspath(__file__))
    if not seed_tsv_path:
        seed_tsv_path = os.path.join(base_dir, 'data', 'hazards', 'road_match_candidates_v1.tsv')
    if not seed_json_path:
        seed_json_path = os.path.join(base_dir, 'data', 'hazards', 'trusted_seed_hazards_v1.json')

    cursor = conn.execute('''
        SELECT id, latitude, longitude, direction_degrees, bidirectional
        FROM speed_bumps
        WHERE needs_map_match = 1
    ''')
    pending_rows = cursor.fetchall()
    if not pending_rows:
        return {'reconciled_count': 0, 'counter_traffic_fixed': 0, 'message': 'No pending new hazards to match'}

    # Load true road bearings from trusted_seed_hazards_v1.json indexed by sourceId
    seed_bearing_map = {}
    if os.path.exists(seed_json_path):
        try:
            with open(seed_json_path, 'r', encoding='utf-8') as jf:
                sdata = json.load(jf)
                for r in sdata.get('rows', []):
                    sid = str(r.get('sourceId', ''))
                    if sid:
                        seed_bearing_map[sid] = float(r.get('directionDegrees', 0.0))
        except Exception:
            pass

    # Load candidate segments from TSV merged with true bearings
    candidates = []
    if os.path.exists(seed_tsv_path):
        with open(seed_tsv_path, 'r', encoding='utf-8') as f:
            for line in f.readlines()[1:]:
                parts = line.strip().split('\t')
                if len(parts) >= 9:
                    try:
                        s_id = parts[0].split(':')[-1]
                        c_lat = float(parts[6])
                        c_lon = float(parts[7])
                        tsv_bearing = float(parts[5]) if parts[5] else 0.0
                        true_bearing = seed_bearing_map.get(s_id, tsv_bearing)
                        candidates.append({
                            'id': parts[0],
                            'road_type': parts[2],      # main, local, service
                            'road_class': parts[3],     # trunk, tertiary, residential, service
                            'oneway': parts[4],         # F, B
                            'bearing': true_bearing,    # True legal flow bearing of this lane (0..360)
                            'lat': c_lat,
                            'lon': c_lon,
                            'parallel_conflict': parts[8].strip() == 'True'
                        })
                    except Exception:
                        continue

    now_str = datetime.now(timezone.utc).isoformat()
    reconciled_count = 0
    counter_traffic_fixed = 0

    for row in pending_rows:
        h_id = row['id'] if isinstance(row, sqlite3.Row) else row[0]
        h_lat = float(row['latitude'] if isinstance(row, sqlite3.Row) else row[1])
        h_lon = float(row['longitude'] if isinstance(row, sqlite3.Row) else row[2])
        h_dir = float((row['direction_degrees'] if isinstance(row, sqlite3.Row) else row[3]) or 0.0)
        h_bi = int((row['bidirectional'] if isinstance(row, sqlite3.Row) else row[4]) or 0)

        best_cand = None
        min_dist = 999999.0

        for cand in candidates:
            if abs(cand['lat'] - h_lat) > 0.003 or abs(cand['lon'] - h_lon) > 0.003:
                continue
            dist = haversine_distance_meters(h_lat, h_lon, cand['lat'], cand['lon'])
            if dist < min_dist:
                min_dist = dist
                best_cand = cand

        # If local candidate is > 35m away, try live OSM road segment geometry lookup
        if not best_cand or min_dist > 35.0:
            osm_cand = _query_osm_road_segment(h_lat, h_lon)
            if osm_cand:
                best_cand = osm_cand
                min_dist = 10.0

        if best_cand and min_dist <= 150.0:
            road_type = best_cand['road_type']
            road_class = best_cand['road_class']
            oneway = best_cand['oneway']
            road_bearing = float(best_cand.get('bearing', h_dir))
            parallel_conflict = 1 if best_cand.get('parallel_conflict') else 0

            corrected_dir = h_dir
            corrected_bi = 1 if oneway == 'B' else 0
            new_status = 'ACTIVE'
            notes_addon = ""

            # Detect counter-traffic (عكس اتجاه):
            # Example: Road lane is Northbound (0 deg, oneway='F'), but car drove counter-traffic Southbound (180 deg)
            # in the Northbound lane and hit a bump.
            if oneway == 'F' and angular_difference_degrees(h_dir, road_bearing) > 120.0:
                counter_traffic_fixed += 1
                corrected_dir = road_bearing
                corrected_bi = 0
                notes_addon = f" [Counter-traffic / عكس اتجاه corrected: {round(h_dir, 1)}° -> {round(corrected_dir, 1)}°]"

                # Check if an active bump already exists on this legal lane within 22m
                lat_d = 30.0 / 111000.0
                lon_d = 30.0 / (111000.0 * max(0.1, math.cos(math.radians(h_lat))))
                existing_legal = conn.execute('''
                    SELECT id, latitude, longitude, direction_degrees
                    FROM speed_bumps
                    WHERE id != ? AND status = 'ACTIVE'
                      AND latitude BETWEEN ? AND ?
                      AND longitude BETWEEN ? AND ?
                ''', (h_id, h_lat - lat_d, h_lat + lat_d, h_lon - lon_d, h_lon + lon_d)).fetchall()

                for ex in existing_legal:
                    ex_id = ex['id'] if isinstance(ex, sqlite3.Row) else ex[0]
                    ex_lat = ex['latitude'] if isinstance(ex, sqlite3.Row) else ex[1]
                    ex_lon = ex['longitude'] if isinstance(ex, sqlite3.Row) else ex[2]
                    ex_dir = float((ex['direction_degrees'] if isinstance(ex, sqlite3.Row) else ex[3]) or 0.0)
                    if haversine_distance_meters(h_lat, h_lon, ex_lat, ex_lon) <= 22.0 and angular_difference_degrees(corrected_dir, ex_dir) <= 55.0:
                        conn.execute('''
                            UPDATE speed_bumps
                            SET verified_count = verified_count + 1,
                                consecutive_negative_passes = 0,
                                last_verified_at = ?
                            WHERE id = ?
                        ''', (now_str, ex_id))
                        new_status = 'MERGED_COUNTER_TRAFFIC'
                        notes_addon += f" [Merged into legal lane bump {ex_id}]"
                        break

            conn.execute('''
                UPDATE speed_bumps
                SET direction_degrees = ?,
                    bidirectional = ?,
                    road_type = ?,
                    road_class = ?,
                    oneway = ?,
                    parallel_conflict = ?,
                    status = ?,
                    needs_map_match = 0,
                    notes = COALESCE(notes, '') || ?,
                    last_verified_at = ?
                WHERE id = ?
            ''', (corrected_dir, corrected_bi, road_type, road_class, oneway, parallel_conflict, new_status, notes_addon, now_str, h_id))
        else:
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
        'counter_traffic_fixed': counter_traffic_fixed,
        'message': f'Successfully reconciled {reconciled_count} new speed bumps on road map (fixed {counter_traffic_fixed} counter-traffic)'
    }
