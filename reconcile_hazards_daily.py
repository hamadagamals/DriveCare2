"""
Daily Hazard & Speed Bump Map Reconciliation Job
Runs once per day on the server to match new user-reported bumps against
the road network, resolve Main Road vs Service Road ambiguities,
and detect counter-traffic (عكس اتجاه) to avoid false warnings on opposing lanes.
"""

import sys
import os
import sqlite3

# Ensure UTF-8 output on Windows
if sys.platform == 'win32':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

ROOT = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(ROOT, 'car_health.db')

import hazard_service

def main():
    print("=" * 60)
    print("DriveCare & RoadGuardian: Daily Speed Bump Map Reconciliation")
    print("=" * 60)
    
    if not os.path.exists(DB_PATH):
        print(f"Error: Database not found at {DB_PATH}")
        sys.exit(1)

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        hazard_service.init_hazard_tables(conn)
        result = hazard_service.run_daily_map_matching(conn)
        print(f"[Daily Job Complete] {result['message']}")
        
        # Summary counts
        active = conn.execute("SELECT COUNT(*) FROM speed_bumps WHERE status = 'ACTIVE'").fetchone()[0]
        deactivated = conn.execute("SELECT COUNT(*) FROM speed_bumps WHERE status = 'DEACTIVATED'").fetchone()[0]
        pending = conn.execute("SELECT COUNT(*) FROM speed_bumps WHERE needs_map_match = 1").fetchone()[0]
        print(f"Current Stats -> Active: {active} | Deactivated: {deactivated} | Pending Map Match: {pending}")
    finally:
        conn.close()

if __name__ == '__main__':
    main()
