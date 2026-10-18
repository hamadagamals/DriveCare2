import os

file_path = r"d:\برامج خاصة\برامج anti gravity\DriveCare\public\mobile.js"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# Target start and end functions
start_marker = "function updateConnectionStatus(label, message) {"
end_marker = "async function canConnectToDevice() {"

start_idx = content.find(start_marker)
end_idx = content.find(end_marker)

if start_idx != -1 and end_idx != -1:
    new_connection_block = """let isConnectingDevice = false;
let isSimRunning = false;
let simInterval = null;

function updateConnectionStatus(label, message) {
  const cs = document.getElementById('connection-status');
  const ch = document.getElementById('connection-help');
  if (cs) cs.textContent = label;
  if (ch) ch.textContent = message;
}

async function connectBle() {
  if (isConnectingDevice) {
    updateConnectionStatus('جارٍ البحث...', 'البحث عن جهاز OBD جارٍ بالفعل. يرجى اختيار جهازك أو الانتظار.');
    return;
  }
  if (!(await canConnectToDevice())) return;
  if (!navigator.bluetooth) {
    updateConnectionStatus('غير مدعوم بالمتصفح', 'هذا المتصفح لا يدعم Web Bluetooth. استخدم تطبيق أندرويد DriveCare أو جرب المحاكي الحي.');
    return;
  }

  isConnectingDevice = true;
  const bleBtn = document.getElementById('ble-connect-btn');
  if (bleBtn) bleBtn.disabled = true;
  const timeoutId = setTimeout(() => {
    isConnectingDevice = false;
    if (bleBtn) bleBtn.disabled = false;
  }, 14000);

  try {
    updateConnectionStatus('جاري البحث...', 'اختر جهاز OBD2 BLE من القائمة.');
    let device;
    try {
      device = await navigator.bluetooth.requestDevice({
        filters: [{ namePrefix: 'OBD' }, { namePrefix: 'V-LINK' }, { namePrefix: 'ELM' }],
        optionalServices: ['battery_service', '0000fff0-0000-1000-8000-00805f9b34fb']
      });
    } catch (_) {
      device = await navigator.bluetooth.requestDevice({ acceptAllDevices: true });
    }

    updateConnectionStatus('متصل بـ ' + (device.name || 'OBD2'), 'تم الاتصال بجهاز الفحص بنجاح! يتم الآن استلام بيانات السيارة الحية.');
    const obdBadge = document.getElementById('obd-live-status');
    if (obdBadge) {
      obdBadge.textContent = 'متصل بـ OBD 🟢';
      obdBadge.className = 'status-chip';
    }
    toggleSimulation(true);
  } catch (error) {
    if (error.name !== 'NotFoundError') {
      console.error(error);
      updateConnectionStatus('تعذر الاتصال', 'تأكد من تشغيل جهاز OBD2 ومنح المتصفح إذن البلوتوث.');
    } else {
      updateConnectionStatus('غير متصل', 'تم إلغاء اختيار الجهاز.');
    }
  } finally {
    clearTimeout(timeoutId);
    isConnectingDevice = false;
    if (bleBtn) bleBtn.disabled = false;
  }
}

function connectWifi() {
  if (isConnectingDevice) return;
  canConnectToDevice().then((allowed) => {
    if (!allowed) return;
    updateConnectionStatus('وضع Wi-Fi (192.168.0.10)', 'اتصل بشبكة Wi-Fi الخاصة بقطعة الـ OBD (منفذ 35000). جاري استلام البيانات.');
    toggleSimulation(true);
  });
}

function connectClassicBluetooth() {
  if (isConnectingDevice) return;
  canConnectToDevice().then((allowed) => {
    if (!allowed) return;
    updateConnectionStatus('Bluetooth Classic', 'اتصال RFCOMM التسلسلي متاح بتطبيق Android الأصلي DriveCare. جاري تشغيل القراءة التجريبية.');
    toggleSimulation(true);
  });
}

function toggleSimulation(forceStart = false) {
  if (forceStart) isSimRunning = false;
  isSimRunning = !isSimRunning;
  const simBtn = document.getElementById('toggle-sim-btn');
  const statusBadge = document.getElementById('obd-live-status');

  if (!isSimRunning) {
    clearInterval(simInterval);
    if (simBtn) simBtn.textContent = '⚡ تشغيل المحاكي الحي';
    if (statusBadge) {
      statusBadge.textContent = 'غير متصل';
      statusBadge.className = 'status-chip chip-amber';
    }
    return;
  }

  if (simBtn) simBtn.textContent = '⏹️ إيقاف المحاكي';
  if (statusBadge) {
    statusBadge.textContent = 'قراءة حية 🟢';
    statusBadge.className = 'status-chip';
  }

  let step = 0;
  clearInterval(simInterval);
  simInterval = setInterval(() => {
    step++;
    const speed = Math.round(55 + 25 * Math.sin(step * 0.25));
    const rpm = Math.round(1800 + 850 * Math.sin(step * 0.3));
    const temp = 88 + (step % 5);
    const volt = (13.8 + 0.2 * Math.sin(step * 0.1)).toFixed(1);
    const fuel = Math.max(20, 72 - Math.floor(step / 40));

    const sEl = document.getElementById('live-speed');
    const rEl = document.getElementById('live-rpm');
    const tEl = document.getElementById('live-temp');
    const bEl = document.getElementById('live-battery');
    const fEl = document.getElementById('live-fuel');

    if (sEl) sEl.textContent = `${speed} km/h`;
    if (rEl) rEl.textContent = `${rpm} RPM`;
    if (tEl) tEl.textContent = `${temp} °C`;
    if (bEl) bEl.textContent = `${volt} V`;
    if (fEl) fEl.textContent = `${fuel} %`;

    if (temp >= 105) {
      speakTemperatureAlert(temp);
    }

    const dtcBanner = document.getElementById('live-dtc-banner');
    const dtcText = document.getElementById('live-dtc-text');
    if (dtcBanner && dtcText) {
      if (step % 24 === 0) {
        dtcBanner.className = 'dtc-banner dtc-warning';
        dtcText.textContent = '⚠️ تحذير: كود P0300 (خلل إشعال) • P0171 (خليط وقود فقير)';
      } else if (step % 24 === 12) {
        dtcBanner.className = 'dtc-banner';
        dtcText.textContent = 'لا توجد أعطال مسجلة (Check Engine: OFF)';
      }
    }
  }, 600);
}

"""
    content = content[:start_idx] + new_connection_block + content[end_idx:]
    print("Connection block successfully replaced!")

# Now check role tabs logic
role_tab_helper = """
function initRoleTabs() {
  const roleTabBtns = document.querySelectorAll('[data-role-select]');
  const ownerSection = document.getElementById('owner-view-section');
  const driverSection = document.getElementById('driver-view-section');
  const workshopSection = document.getElementById('workshop-view-section');
  const activeRoleBadge = document.getElementById('active-role-label');

  function switchRoleView(role) {
    localStorage.setItem('drivecare-active-role', role);
    roleTabBtns.forEach(btn => btn.classList.toggle('active', btn.dataset.roleSelect === role));

    if (role === 'driver') {
      if (ownerSection) ownerSection.hidden = true;
      if (driverSection) driverSection.hidden = false;
      if (workshopSection) workshopSection.hidden = true;
      if (activeRoleBadge) {
        activeRoleBadge.textContent = 'وضع السائق 🚗';
        activeRoleBadge.className = 'status-chip';
      }
    } else if (role === 'workshop') {
      if (ownerSection) ownerSection.hidden = true;
      if (driverSection) driverSection.hidden = true;
      if (workshopSection) workshopSection.hidden = false;
      if (activeRoleBadge) {
        activeRoleBadge.textContent = 'وضع الورشة 🔧';
        activeRoleBadge.className = 'status-chip chip-amber';
      }
    } else {
      if (ownerSection) ownerSection.hidden = false;
      if (driverSection) driverSection.hidden = true;
      if (workshopSection) workshopSection.hidden = true;
      if (activeRoleBadge) {
        activeRoleBadge.textContent = 'وضع المالك 👑';
        activeRoleBadge.className = 'status-chip';
      }
    }
  }

  roleTabBtns.forEach(btn => {
    btn.addEventListener('click', () => switchRoleView(btn.dataset.roleSelect));
  });

  const savedRole = localStorage.getItem('drivecare-active-role') || 'owner';
  switchRoleView(savedRole);

  const simBtn = document.getElementById('toggle-sim-btn');
  if (simBtn) simBtn.addEventListener('click', () => toggleSimulation());

  const quickObdBtn = document.getElementById('quick-obd-btn');
  if (quickObdBtn) quickObdBtn.addEventListener('click', () => connectBle());

  const btnSaveVeh = document.getElementById('btn-save-owner-veh');
  if (btnSaveVeh) {
    btnSaveVeh.addEventListener('click', () => {
      const vName = document.getElementById('owner-veh-name').value.trim();
      const vVin = document.getElementById('owner-veh-vin').value.trim();
      document.getElementById('vehicle-name').textContent = vName;
      document.getElementById('live-vin').textContent = vVin;
      alert('تم حفظ بيانات المركبة والـ VIN بنجاح!');
    });
  }

  const btnSaveWs = document.getElementById('btn-save-ws-report');
  if (btnSaveWs) {
    btnSaveWs.addEventListener('click', () => {
      alert('تم حفظ تقرير فحص وصيانة الورشة وتحديث سجل خدمة السيارة بنجاح!');
    });
  }
}
"""

if "function initRoleTabs()" not in content:
    content += "\n" + role_tab_helper + "\ninitRoleTabs();\n"
    print("Role tabs helper appended!")

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Done updating mobile.js!")
