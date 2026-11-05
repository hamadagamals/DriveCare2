import importlib.util
import json
import tempfile
import threading
import unittest
from http.client import HTTPConnection
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('drivecare_server', PROJECT_ROOT / 'server.py')
server_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server_module)


class DriveCareSecurityTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.original_db_path = server_module.DB_PATH
        server_module.DB_PATH = str(Path(self.temp_dir.name) / 'car_health.db')
        server_module.init_db()
        self.httpd = server_module.ThreadingHTTPServer(('127.0.0.1', 0), server_module.Handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        server_module.DB_PATH = self.original_db_path
        self.temp_dir.cleanup()

    def request(self, method, path, payload=None, token=None):
        headers = {}
        body = None
        if payload is not None:
            body = json.dumps(payload)
            headers['Content-Type'] = 'application/json'
        if token:
            headers['Authorization'] = f'Bearer {token}'
        connection = HTTPConnection('127.0.0.1', self.httpd.server_address[1], timeout=3)
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        data = json.loads(response.read().decode('utf-8'))
        connection.close()
        return response.status, data

    def test_new_hash_is_salted_and_verifiable(self):
        first = server_module.hash_password('a long enough password')
        second = server_module.hash_password('a long enough password')
        self.assertNotEqual(first, second)
        self.assertTrue(server_module.verify_password('a long enough password', first)[0])
        self.assertFalse(server_module.verify_password('wrong password', first)[0])

    def test_vehicle_data_requires_login_and_does_not_fall_back_to_demo_data(self):
        status, _ = self.request('GET', '/api/vehicle')
        self.assertEqual(status, 401)

        status, signup = self.request('POST', '/api/auth/signup', {
            'name': 'Secure Owner', 'phone': '+201234567890',
            'password': 'a secure password', 'roles': ['owner'],
        })
        self.assertEqual(status, 201)
        status, no_vehicle = self.request('GET', '/api/vehicle', token=signup['token'])
        self.assertEqual(status, 403)
        self.assertEqual(no_vehicle['error'], 'No accessible vehicle')
        status, created = self.request('POST', '/api/vehicles', {
            'action': 'create', 'vehicle': {'name': 'First Car'}
        }, signup['token'])
        self.assertEqual(status, 201)
        status, vehicle = self.request('GET', '/api/vehicle', token=signup['token'])
        self.assertEqual(status, 200)
        self.assertEqual(vehicle['name'], 'First Car')

    def test_session_survives_memory_reset_and_logout_revokes_it(self):
        status, signup = self.request('POST', '/api/auth/signup', {
            'name': 'Owner Two', 'phone': '+201234567891',
            'password': 'another secure password', 'roles': ['owner'],
        })
        self.assertEqual(status, 201)
        token = signup['token']
        status, created = self.request('POST', '/api/vehicles', {
            'action': 'create', 'vehicle': {'name': 'Test Car'}
        }, token)
        self.assertEqual(status, 201)
        self.assertTrue(created['vehicleId'])
        status, vehicle = self.request('GET', '/api/vehicle', token=token)
        self.assertEqual(status, 200)
        self.assertEqual(vehicle['name'], 'Test Car')
        status, _ = self.request('POST', '/api/auth/logout', {}, token)
        self.assertEqual(status, 200)
        status, _ = self.request('GET', '/api/vehicle', token=token)
        self.assertEqual(status, 401)

    def test_authenticated_password_change_revokes_previous_sessions(self):
        status, signup = self.request('POST', '/api/auth/signup', {
            'name': 'Password Owner', 'phone': '+201234567899',
            'password': 'initial secure password', 'roles': ['owner'],
        })
        self.assertEqual(status, 201)
        old_token = signup['token']

        status, changed = self.request('POST', '/api/auth/change-password', {
            'currentPassword': 'initial secure password',
            'newPassword': 'replacement secure password',
        }, old_token)
        self.assertEqual(status, 200)
        self.assertIn('token', changed)

        status, _ = self.request('GET', '/api/vehicle', old_token)
        self.assertEqual(status, 401)
        status, _ = self.request('POST', '/api/auth/login', {
            'phone': '+201234567899', 'password': 'replacement secure password',
        })
        self.assertEqual(status, 200)

    def test_login_by_email_and_add_workshop_role_to_same_account(self):
        status, signup = self.request('POST', '/api/auth/signup', {
            'name': 'Multi Role User', 'phone': '+201234567898', 'email': 'multi@example.test',
            'password': 'multirole secure password', 'roles': ['owner'],
        })
        self.assertEqual(status, 201)
        status, login = self.request('POST', '/api/auth/login', {
            'email': 'multi@example.test', 'password': 'multirole secure password',
        })
        self.assertEqual(status, 200)
        status, rejected = self.request('POST', '/api/account/roles', {'role': 'workshop'}, login['token'])
        self.assertEqual(status, 400)
        self.assertIn('Workshop', rejected['error'])
        status, added = self.request('POST', '/api/account/roles', {
            'role': 'workshop',
            'workshop': {'name': 'Role Garage', 'phone': '+201234567897', 'services': 'diagnostics'},
        }, login['token'])
        self.assertEqual(status, 200)
        self.assertEqual(set(added['roles']), {'owner', 'workshop'})

    def test_workshop_sees_only_explicitly_approved_fields_without_owner_identity(self):
        status, owner = self.request('POST', '/api/auth/signup', {
            'name': 'Vehicle Owner', 'phone': '+201234567892',
            'password': 'owner secure password', 'roles': ['owner'],
        })
        self.assertEqual(status, 201)
        owner_token = owner['token']
        status, vehicle = self.request('POST', '/api/vehicles', {
            'action': 'create', 'vehicle': {'name': 'Private Car'}
        }, owner_token)
        self.assertEqual(status, 201)

        status, workshop = self.request('POST', '/api/auth/signup', {
            'name': 'Workshop User', 'phone': '+201234567893',
            'password': 'workshop secure password', 'roles': ['workshop'],
            'workshop': {'name': 'Secure Garage', 'phone': '+201234567894', 'services': 'diagnostics'},
        })
        self.assertEqual(status, 201)
        with server_module.get_connection() as conn:
            workshop_id = conn.execute(
                'SELECT id FROM workshops WHERE owner_user_id = ?', (workshop['userId'],)
            ).fetchone()['id']
            conn.execute(
                '''INSERT INTO workshop_access_requests
                   (id, workshop_id, owner_user_id, vehicle_id, status, permissions, expires_at)
                   VALUES (?, ?, ?, ?, 'approved', ?, ?)''',
                ('approved-access', workshop_id, owner['userId'], vehicle['vehicleId'],
                 json.dumps(['diagnostics']), '2099-01-01T00:00:00+00:00')
            )
            conn.commit()

        status, visible = self.request('GET', '/api/garage/vehicles', token=workshop['token'])
        self.assertEqual(status, 200)
        self.assertEqual(len(visible), 1)
        self.assertEqual(visible[0]['vehicleName'], 'Private Car')
        self.assertIsNone(visible[0]['healthScore'])
        self.assertIsNone(visible[0]['maintenanceCount'])
        self.assertNotIn('ownerName', visible[0])

    def test_vin_lock_blocks_stranger_obd_pair_and_allows_delegated_driver(self):
        # 1. Owner signs up, creates a vehicle, and locks its VIN on the server
        status, owner = self.request('POST', '/api/auth/signup', {
            'name': 'Car Owner', 'phone': '01011112222',
            'password': 'owner secure password', 'roles': ['owner'],
        })
        self.assertEqual(status, 201)
        status, veh = self.request('POST', '/api/vehicles', {
            'action': 'create', 'vehicle': {'name': 'Chevrolet Aveo'}
        }, owner['token'])
        self.assertEqual(status, 201)
        veh_id = veh['vehicleId']

        vin = 'KMHDH41EBNA123456'
        status, locked = self.request('POST', '/api/vin/lock', {
            'action': 'register', 'vin': vin, 'vehicleId': veh_id
        }, owner['token'])
        self.assertEqual(status, 200)
        self.assertIn('locked', locked['status'])

        # 2. Unauthorized stranger tries to scan & pair OBD with this VIN -> MUST BE BLOCKED (403)
        status, stranger = self.request('POST', '/api/auth/signup', {
            'name': 'Stranger User', 'phone': '01099998888',
            'password': 'stranger secure password', 'roles': ['driver'],
        })
        self.assertEqual(status, 201)
        status, blocked = self.request('POST', '/api/vin/lock', {
            'action': 'verify_obd_pair', 'vin': vin, 'vehicleId': veh_id
        }, stranger['token'])
        self.assertEqual(status, 403)
        self.assertFalse(blocked.get('allowed', True))

        # 3. Delegated driver signs up and Owner assigns/delegates the car to the driver
        status, driver = self.request('POST', '/api/auth/signup', {
            'name': 'Authorized Driver', 'phone': '01055556666',
            'password': 'driver secure password', 'roles': ['driver'],
        })
        self.assertEqual(status, 201)
        status, assigned = self.request('POST', '/api/drivers', {
            'action': 'assign', 'phone': '01055556666', 'vehicleId': veh_id
        }, owner['token'])
        self.assertEqual(status, 201)

        # 4. Delegated driver scans & pairs with the car's OBD reader -> ALLOWED (200) under Owner's authority!
        status, allowed_pair = self.request('POST', '/api/vin/lock', {
            'action': 'verify_obd_pair', 'vin': vin, 'vehicleId': veh_id
        }, driver['token'])
        self.assertEqual(status, 200)
        self.assertTrue(allowed_pair['allowed'])
        self.assertEqual(allowed_pair['authRole'], 'driver')
        self.assertEqual(allowed_pair['ownerName'], 'Car Owner')

        # 5. Device access check also confirms the delegated driver can pair & stream telemetry
        status, dev_check = self.request('POST', '/api/device-access', {
            'action': 'check', 'vehicleId': veh_id
        }, driver['token'])
        self.assertEqual(status, 200)
        self.assertTrue(dev_check['allowed'])


if __name__ == '__main__':
    unittest.main()
