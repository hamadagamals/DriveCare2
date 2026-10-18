const $ = (selector) => document.querySelector(selector);

const tabs = document.querySelectorAll('.tab');
const panels = document.querySelectorAll('.panel');
const addVehicleBtn = $('#add-vehicle-btn');
const exportBtn = $('#export-btn');

const STORAGE_KEY = 'drivecare-demo-token';
let selectedVehicleId = null;

function showToast(message) {
  let toast = $('#toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'toast';
    toast.className = 'toast';
    document.body.appendChild(toast);
  }
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove('show'), 2000);
}

function getDemoToken() {
  return localStorage.getItem(STORAGE_KEY);
}

function setDemoToken(token) {
  localStorage.setItem(STORAGE_KEY, token);
}

async function ensureDemoSession() {
  let token = getDemoToken();
  if (token) return token;

  const email = `demo-${Date.now()}@drivecare.local`;
  const response = await fetch('/api/auth/signup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: 'Owner Demo',
      email,
      password: 'demo1234'
    })
  });

  if (!response.ok) {
    throw new Error('Failed to create demo session');
  }

  const data = await response.json();
  setDemoToken(data.token);
  return data.token;
}

async function loadJson(url, options = {}) {
  const token = getDemoToken();
  const requestUrl = selectedVehicleId && !url.includes('vehicleId=')
    ? `${url}${url.includes('?') ? '&' : '?'}vehicleId=${encodeURIComponent(selectedVehicleId)}`
    : url;

  const response = await fetch(requestUrl, {
    ...options,
    headers: {
      ...(options.headers || {}),
      ...(token ? { Authorization: token } : {})
    }
  });

  if (!response.ok) throw new Error('Network error: ' + requestUrl);
  return response.json();
}

tabs.forEach((tab) => {
  tab.addEventListener('click', () => {
    tabs.forEach((t) => t.classList.toggle('active', t === tab));
    panels.forEach((panel) => {
      panel.classList.toggle('active', panel.id === tab.dataset.target);
    });
  });
});

function renderVehicle(data) {
  $('#vehicle-name').textContent = data.name;
  $('#vehicle-status').textContent = data.status || 'healthy';
  $('#health-score').textContent = `${data.healthScore ?? data.health_score ?? 82}`;
  $('#fuel-level').textContent = `${data.fuelLevel ?? 68}%`;
  $('#battery-level').textContent = `${data.battery ?? 13.9}V`;
  $('#engine-temp').textContent = `${data.engineTemp ?? 88}°C`;
  $('#vehicle-speed').textContent = `${data.speed ?? 62} km/h`;
  $('#service-due').textContent = data.nextService || 'في 380 كم';
}

function renderVehicleList(vehicles) {
  const list = $('#vehicle-list');
  if (!vehicles || !vehicles.length) {
    list.innerHTML = '<div class="empty-state">لا توجد سيارات حتى الآن.</div>';
    return;
  }

  list.innerHTML = vehicles.map((vehicle) => `
    <button class="vehicle-item" type="button" data-id="${vehicle.id}">
      <div>
        <strong>${vehicle.name}</strong>
        <span>${vehicle.make || 'Unknown'} ${vehicle.model || ''}</span>
      </div>
      <span class="tag ${vehicle.healthScore >= 80 ? 'good' : 'warning'}">${vehicle.healthScore ?? 82}</span>
    </button>
  `).join('');

  list.querySelectorAll('.vehicle-item').forEach((button) => {
    button.addEventListener('click', async () => {
      const selectedId = button.dataset.id;
      const chosen = vehicles.find((item) => item.id === selectedId);
      if (!chosen) return;

      selectedVehicleId = selectedId;
      renderVehicle({
        name: chosen.name,
        status: chosen.status || 'healthy',
        healthScore: chosen.healthScore || 82,
        fuelLevel: 68,
        battery: 13.9,
        engineTemp: 88,
        speed: 62,
        nextService: 'في 380 كم'
      });
      await bootstrap();
    });
  });
}

function renderRetentionInfo(data) {
  const retention = data?.retention || {};
  const health = data?.health || {};
  const insights = data?.insights || [];

  $('#retention-policy').textContent = retention.policy || 'تخزين ذكي';
  $('#retention-trend').textContent = retention.healthSignal?.trend || '+0.0%';
  $('#retention-records').textContent = `${retention.summaryRecords || 0} سجل`;

  $('#insight-list').innerHTML = insights.map((item) => `
    <div class="insight-item">${item}</div>
  `).join('');
}

function renderTripList(data) {
  const list = $('#trip-list');
  list.innerHTML = data.map((trip) => `
    <div class="stack-item">
      <div class="meta">
        <strong>${trip.route}</strong>
        <span>${trip.date}</span>
      </div>
      <div class="meta">
        <strong>${trip.distance} km</strong>
        <span>${trip.fuelUsed} L • ${trip.cost} SAR</span>
      </div>
      <span class="tag info">${trip.average} L/100km</span>
    </div>
  `).join('');
}

function renderMaintenance(data) {
  const list = $('#maintenance-list');
  list.innerHTML = data.map((item) => {
    const tagClass = item.urgency === 'warning' ? 'warning' : item.urgency === 'low' ? 'info' : 'good';
    return `
      <div class="stack-item">
        <div class="meta">
          <strong>${item.item}</strong>
          <span>${item.dueIn}</span>
        </div>
        <span class="tag ${tagClass}">${item.status}</span>
      </div>
    `;
  }).join('');
}

function renderDiagnostics(data) {
  const list = $('#diagnostic-list');
  list.innerHTML = data.map((item) => {
    const tagClass = item.severity === 'high' ? 'danger' : item.severity === 'medium' ? 'warning' : 'info';
    return `
      <div class="stack-item">
        <div class="meta">
          <strong>${item.code} - ${item.title}</strong>
          <span>${item.summary}</span>
        </div>
        <span class="tag ${tagClass}">${item.severity}</span>
      </div>
    `;
  }).join('');
}

function renderAlerts(data) {
  const list = $('#alert-list');
  list.innerHTML = data.map((item) => {
    const tagClass = item.type === 'warning' ? 'warning' : item.type === 'safe' ? 'good' : 'info';
    return `
      <li class="list-item">
        <div class="meta">
          <strong>${item.title}</strong>
          <span>${item.detail}</span>
        </div>
        <span class="tag ${tagClass}">${item.time}</span>
      </li>
    `;
  }).join('');
}

function renderHealthIndicators(data) {
  const list = $('#health-indicators');
  const entries = [
    { label: 'استهلاك الوقود', value: `${data.fuelEfficiency ?? 8.3} L/100km`, tag: (data.fuelEfficiency ?? 8.3) > 9 ? 'warning' : 'good', tagLabel: (data.fuelEfficiency ?? 8.3) > 9 ? 'تنبيه' : 'مستقر' },
    { label: 'متوسط RPM', value: `${data.engine?.avgRpm ?? 1950} RPM`, tag: 'info', tagLabel: 'مقبول' },
    { label: 'طاقة المحرك', value: `${data.engine?.idleTime ?? '12%'} idle`, tag: data.engine?.status === 'stable' ? 'good' : 'warning', tagLabel: data.engine?.status === 'stable' ? 'جيد' : 'مراجعة' },
    { label: 'الحالة العامة', value: data.summary || 'متوسط', tag: data.score >= 80 ? 'good' : 'warning', tagLabel: data.score >= 80 ? 'جيدة' : 'مراقبة' }
  ];

  list.innerHTML = entries.map((entry) => `
    <div class="indicator-row">
      <div class="meta">
        <strong>${entry.label}</strong>
        <span>${entry.value}</span>
      </div>
      <span class="tag ${entry.tag}">${entry.tagLabel}</span>
    </div>
  `).join('');
}

function renderTrendBars(data) {
  const container = $('#trend-bars');
  const values = [72, 76, 81, 79, 84, 88];
  const max = 100;

  container.innerHTML = values.map((value, index) => `
    <div class="trend-item">
      <span>${index + 1}</span>
      <div class="trend-track"><div class="trend-fill" style="height:${(value / max) * 100}%"></div></div>
    </div>
  `).join('');
}

function renderProfile(data) {
  const list = $('#profile-list');
  const entries = [
    ['اسم السيارة', data.name],
    ['الشركة', data.make || 'Toyota'],
    ['الطراز', data.model || 'Corolla'],
    ['السنة', data.year || 2021],
    ['نوع الوقود', data.fuel_type || 'بنزين'],
    ['عداد السيارة', `${data.odometer_km || 28450} كم`],
    ['حالة السيارة', data.status || 'healthy'],
    ['Health Score', `${data.health_score || 82}/100`]
  ];

  list.innerHTML = entries.map(([label, value]) => `
    <div class="profile-item">
      <span>${label}</span>
      <strong>${value}</strong>
    </div>
  `).join('');
}

function renderSettings(data) {
  const list = $('#settings-list');
  const settings = [
    { label: 'إشعارات الصيانة', value: data.maintenance ? 'مفعلة' : 'موقفة', tag: 'good' },
    { label: 'تنبيهات محرك', value: data.engineAlerts ? 'مفعلة' : 'موقفة', tag: 'good' },
    { label: 'تصدير البيانات', value: 'CSV / JSON', tag: 'info' },
    { label: 'نسخ احتياطي تلقائي', value: 'كل 7 أيام', tag: 'info' },
    { label: 'الوحدة', value: 'كم / لتر / SAR', tag: 'good' }
  ];

  list.innerHTML = settings.map((item) => `
    <div class="stack-item">
      <div class="meta">
        <strong>${item.label}</strong>
      </div>
      <span class="tag ${item.tag}">${item.value}</span>
    </div>
  `).join('');
}

async function handleAddVehicle() {
  const name = $('#vehicle-name-input').value.trim();
  const make = $('#vehicle-make-input').value.trim() || 'Toyota';
  const model = $('#vehicle-model-input').value.trim() || 'Corolla';
  const year = Number($('#vehicle-year-input').value || 2024);

  if (!name) {
    alert('يرجى إدخال اسم السيارة');
    return;
  }

  const token = await ensureDemoSession();
  const response = await fetch('/api/vehicles', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: token
    },
    body: JSON.stringify({
      action: 'create',
      vehicle: {
        name,
        make,
        model,
        year,
        fuelType: 'gasoline',
        odometerKm: 12000,
        status: 'healthy',
        healthScore: 84
      }
    })
  });

  if (!response.ok) {
    alert('تعذر إضافة السيارة');
    return;
  }

  $('#vehicle-name-input').value = '';
  $('#vehicle-make-input').value = '';
  $('#vehicle-model-input').value = '';
  $('#vehicle-year-input').value = '2024';
  await bootstrap();
}

async function handleExport() {
  const token = await ensureDemoSession();
  const response = await fetch('/api/export', {
    headers: { Authorization: token }
  });

  if (!response.ok) {
    alert('تعذر تصدير البيانات');
    return;
  }

  const data = await response.json();
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'drivecare-export.json';
  a.click();
  URL.revokeObjectURL(url);
  showToast('تم تصدير ملف JSON احتياطي بنجاح');
}

function handleLogout() {
  localStorage.removeItem(STORAGE_KEY);
  selectedVehicleId = null;
  $('#vehicle-name-input').value = '';
  $('#vehicle-make-input').value = '';
  $('#vehicle-model-input').value = '';
  $('#vehicle-year-input').value = '2024';
  showToast('تم تسجيل الخروج');
  bootstrap();
}

async function bootstrap() {
  try {
    await ensureDemoSession();

    const [dashboard, trips, maintenance, diagnostics, alerts, health, profile, vehicles] = await Promise.all([
      loadJson('/api/dashboard'),
      loadJson('/api/trips'),
      loadJson('/api/maintenance'),
      loadJson('/api/diagnostics'),
      loadJson('/api/alerts'),
      loadJson('/api/health'),
      loadJson('/api/vehicle'),
      loadJson('/api/vehicles')
    ]);

    renderVehicle(dashboard.vehicle);
    const allVehicles = vehicles.vehicles || [];
    if (!selectedVehicleId && allVehicles.length) {
      selectedVehicleId = allVehicles[0].id;
    }
    renderVehicleList(allVehicles);
    $('#trip-distance').textContent = `${dashboard.summary.kmToday} كم`;
    $('#fuel-used').textContent = `${dashboard.summary.fuelUsedToday} L`;
    $('#service-due').textContent = dashboard.summary.serviceDue;
    renderRetentionInfo({ retention: dashboard.retention, insights: dashboard.insights, health });
    renderTripList(trips);
    renderMaintenance(maintenance);
    renderDiagnostics(diagnostics);
    renderAlerts(alerts);
    renderHealthIndicators(health);
    renderTrendBars(health);
    renderProfile(profile);
    renderSettings({ maintenance: true, engineAlerts: true, units: 'metric' });
  } catch (error) {
    console.error(error);
  }
}

const logoutButton = document.getElementById('logout-btn');
addVehicleBtn.addEventListener('click', handleAddVehicle);
exportBtn.addEventListener('click', handleExport);
logoutButton.addEventListener('click', handleLogout);
bootstrap();
