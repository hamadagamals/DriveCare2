import os
import subprocess
import sys

os.environ['DRIVECARE_HOST'] = '0.0.0.0'
os.environ['DRIVECARE_ALLOW_LAN'] = '1'
os.environ['DRIVECARE_PORT'] = '3000'

import server
server.init_db()
print(f"DriveCare running at http://0.0.0.0:3000")
srv = server.ThreadingHTTPServer(('0.0.0.0', 3000), server.Handler)
srv.serve_forever()
