async function loadJson(url) {
  const token = localStorage.getItem('carHealthToken');
  if (!token) {
    window.location.replace('/mobile');
    throw new Error('Authentication required');
  }
  const response = await fetch(url, { headers: { Authorization: token } });
  if (!response.ok) throw new Error('Request failed: ' + url);
  return response.json();
}

function renderVehicles(vehicles) {
  const root = document.getElementById('garage-vehicles');
  root.innerHTML = vehicles.map((vehicle) => {
    const severityClass = vehicle.healthScore == null
      ? ''
      : vehicle.healthScore < 60 ? 'danger' : vehicle.healthScore < 75 ? 'warning' : 'good';
    return `
      <div class="stack-item">
        <div class="meta">
          <strong>${vehicle.vehicleName}</strong>
          <span>الصلاحيات: ${(vehicle.permissions || []).join('، ') || 'لا توجد بيانات معتمدة'}</span>
        </div>
        <div class="meta">
          <strong>${vehicle.status}</strong>
          <span>${vehicle.maintenanceCount ?? '—'} صيانة • ${vehicle.diagnosticsCount ?? '—'} أعطال</span>
        </div>
        <span class="tag ${severityClass}">${vehicle.healthScore ?? '—'}${vehicle.healthScore == null ? '' : '/100'}</span>
      </div>
    `;
  }).join('');
}

function renderReports(reports) {
  const root = document.getElementById('garage-reports');
  root.innerHTML = reports.map((report) => {
    const severityClass = report.healthScore == null
      ? ''
      : report.healthScore < 60 ? 'danger' : report.healthScore < 75 ? 'warning' : 'good';
    return `
    <div class="stack-item">
      <div class="meta">
        <strong>${report.vehicleName}</strong>
        <span>بيانات بموافقة صاحب السيارة فقط</span>
      </div>
      <div class="meta">
        <strong>Health: ${report.healthScore ?? '—'}${report.healthScore == null ? '' : '/100'}</strong>
        <span>${report.summary || 'لا توجد صلاحية لعرض ملخص الحالة'}</span>
      </div>
      <span class="tag ${severityClass}">${report.diagnosticsCount ?? '—'} أعطال</span>
    </div>
  `;
  }).join('');
}

async function bootstrap() {
  try {
    const vehicles = await loadJson('/api/garage/vehicles');
    const reports = await loadJson('/api/garage/reports');
    document.getElementById('total-cars').textContent = vehicles.length;
    document.getElementById('critical-cars').textContent = vehicles.filter((v) => v.healthScore != null && v.healthScore < 60).length;
    document.getElementById('open-reports').textContent = reports.length;
    renderVehicles(vehicles);
    renderReports(reports);
  } catch (error) {
    console.error(error);
  }
}

bootstrap();
