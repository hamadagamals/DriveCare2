# Car Health App Architecture

## 1. Goal
Build a professional owner-car app first, then add a garage/admin companion app using the same backend and data model.

## 2. Current scope
- Owner app dashboard
- Vehicle profile
- Trips and fuel tracking
- Maintenance calendar
- Active diagnostics
- Alerts and health score
- Local-first design with summary-based storage
- Shared backend for future garage/admin app

## 3. Shared backend modules
- Auth
- Vehicles
- Trips
- Maintenance
- Diagnostics
- Alerts
- Health score
- Export and backup
- Sync and notifications

## 4. Proposed app split
### Owner app
- Dashboard
- Trips
- Maintenance
- Diagnostics
- Alerts
- Settings
- Vehicle info

### Garage/Admin app
- Vehicle list
- Diagnostic review
- Maintenance tasks
- Repair notes
- Health summaries
- Service reports
- User communication

## 5. Recommended technology stack
- Frontend: Flutter or React Native
- Local storage: SQLite / Hive / Room
- Cloud backend: Supabase or Firebase
- Notification: FCM
- Realtime: Supabase Realtime / Firebase listeners

## 6. Low-storage strategy
- Store only changed values in local storage
- Record threshold-based diffs
- Aggregate per 5-minute interval
- Save daily summaries and trip summaries
- Archive older data after 30-90 days
- Export CSV/JSON for offline backup

## 7. Integration strategy
Use shared backend and shared database contracts instead of merging the apps directly in code during the first phase. Later, a shared admin panel or companion app can consume the same APIs.

## 8. Next implementation steps
1. Improve API separation with routes and service modules
2. Add auth and multi-vehicle support
3. Add garage dashboard app API contracts
4. Add export/backup endpoint
5. Prepare mobile-first polish for production
