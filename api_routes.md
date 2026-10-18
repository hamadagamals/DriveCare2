# API Contract Draft

## Base URL
http://localhost:3000/api

## Endpoints
### GET /dashboard
Returns summary and current vehicle state.

### GET /vehicle
Returns vehicle profile data.

### GET /trips
Returns trip history and fuel summaries.

### GET /maintenance
Returns scheduled or active maintenance tasks.

### GET /diagnostics
Returns active diagnostic codes and issues.

### GET /alerts
Returns active alerts and warnings.

### GET /health
Returns health score and trend information.

### GET /settings
Returns app settings and preferences.

### POST /vehicles
Create a new vehicle.

### POST /trips
Create or update trip record.

### POST /maintenance
Create or update maintenance task.

### POST /alerts
Create an alert or warning.

### POST /export
Export JSON or CSV data for backup.

## Shared data model
- user
- vehicle
- trip
- maintenance_task
- diagnostic_code
- alert
- health_score
- sensor_snapshot

## Future garage/admin extensions
- /garage/vehicles
- /garage/diagnostics
- /garage/reports
- /garage/service-notes
- /garage/vehicle-status
