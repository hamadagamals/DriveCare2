# 📐 وثيقة المواصفات الفنية وطرق الحساب والقياس الرياضية
## Technical Measurement & Calculation Specifications
> **ملاحظة:** هذا الملف هو مرجع تقني محفوظ في مستودع الأكواد للذكاء الاصطناعي والمطورين، ولا يتم عرضه داخل واجهة تطبيق الموبايل تفادياً للازدحام البصري بناءً على توجيهات المالك.

---

### 1. الكشف الحركي والفيزيائي للمطبات الأرضية (Kinematic Speed Bump Detection)
يعتمد محرك `SpeedBumpManager.kt` و `hazard_service.py` على تحليل ثلاثي الأبعاد لحركة المركبة باستخدام مقياس التسارع (Accelerometer) ومستقبل الـ GPS:

#### أ. خط الأساس للجاذبية الأرضية التكيفي (Dynamic Gravity Baseline LPF)
لمعادلة وضع الهاتف داخل الحامل وتفادي تأثير زاوية تثبيت الهاتف أو ميل الطريق:
$$g_{baseline}(t) = \alpha \cdot g_{baseline}(t-1) + (1 - \alpha) \cdot g_{raw}(t)$$
حيث معامل التنعيم $\alpha = 0.95$.

#### ب. تمايز الصدمة العمودية والارتداد (Vertical Shock & Rebound Profile)
يتم قياس التسارع الرأسي الصافي:
$$\Delta a_z = |a_z(t) - g_{baseline}(t)|$$
- **عتبة الصدمة المبدئية (Shock Inception):** $\Delta a_z \ge 2.8 \text{ m/s}^2$
- **الارتداد العكسي (Rebound Wave):** وجود قمة معاكسة في المحور $Z$ خلال نافذة زمنية تتراوح بين $120 \text{ ms}$ و $450 \text{ ms}$.
- **نسبة التخميد (Damping Ratio):** استقرار التسارع خلال $800 \text{ ms}$ دون استمرار الاهتزاز (لتمييز المطب عن الطرق الوعرة أو الحصى).

#### ج. بوابة السرعة الكينماتيكية ودورة حياة المطب (Kinematic Speed Gate & 3-Pass Lifecycle)
- **شرط تسجيل وتأكيد المطب (Strict Speed Gate):** يتم تسجيل أو تأكيد وجود مطب حصراً عندما تكون سرعة المركبة أثناء عبور النمط الاهتزازي:
  $$3.0 \text{ km/h} \le v_{vehicle} \le 21.0 \text{ km/h}$$
  مع تحقق نمط المطب المتعارف عليه (تهدئة السرعة $\le 21 \text{ km/h}$ + صدمة عمودية لأعلى $\Delta a_z \ge 1.8 \text{ m/s}^2$ + ارتداد عكسي لنظام التعليق + ميلان طولي Gyro Pitch).
- **الإلغاء التلقائي عند مرور 3 سيارات متتالية وإعادة التفعيل التلقائي:**
  - إذا عبرت **3 سيارات متتالية** فوق موقع المطب في **نفس اتجاه المسار** ($\Delta \theta \le 55^\circ$) بسرعة طبيعية دون حدوث تهدئة أو اهتزاز ($\text{consecutive\_negative\_passes} \ge 3$)، يتم إيقاف/إزالة تفعيل المطب تلقائياً (`status = 'DEACTIVATED'`).
  - **إعادة التفعيل عند عودة النمط:** إذا عاد نمط المطب القياسي ($\le 21 \text{ km/h}$ + اهتزاز وارتداد التعليق) لسيارة أخرى في نفس الموقع والاتجاه، يعيد النظام تفعيل المطب فوراً (`status = 'ACTIVE'` وتصفير عداد العبور السالب).

#### د. مطابقة خرائط الطرق اليومية ومنع التسجيل الوهمي عكس الاتجاه (Daily Counter-Traffic Road Map Reconciliation)
- لمنع تسجيل مطب وهمي في الاتجاه المعاكس عندما تسير مركبة **عكس السير القانوني** (مثال: السير جنوباً $180^\circ$ داخل مسار متجه شمالاً $0^\circ$):
  - يعمل محرك المطابقة الجغرافية مرة واحدة يومياً (`run_daily_map_matching`) للمطبات المسجلة حديثاً فقط (`needs_map_match = 1`).
  - يقارن المحرك زاوية سير المركبة المسجلة مع زاوية المسار الحقيقية للطريق (`oneway`) من خرائط الطرق (OSM Overpass + مقاطع الطرق المعتمدة):
    $$\Delta \theta = |\theta_{vehicle} - \theta_{road\_lane}| > 120^\circ$$
  - عند رصد سير عكس الاتجاه على حارة أحادية، يقوم النظام بتصحيح اتجاه المطب تلقائياً إلى الاتجاه القانوني الصحيح للحارة ($\theta_{road\_lane}$) أو دمجه مع المطب القائم على تلك الحارة.

#### هـ. مسافة الاقتراب وقانون نصف الجيب التمام الكروي (Haversine Formula)
لحساب المسافة بين إحداثيات المركبة وموقع المطب في قاعدة البيانات:
$$\Delta \phi = \phi_2 - \phi_1, \quad \Delta \lambda = \lambda_2 - \lambda_1$$
$$a = \sin^2\left(\frac{\Delta \phi}{2}\right) + \cos(\phi_1)\cos(\phi_2)\sin^2\left(\frac{\Delta \lambda}{2}\right)$$
$$a_{clamped} = \min(1.0, \max(0.0, a))$$
$$d = 2 R \cdot \arctan2\left(\sqrt{a_{clamped}}, \sqrt{1 - a_{clamped}}\right)$$
حيث نصف قطر الأرض $R = 6,371,000 \text{ m}$.

- **التنبيه الصوتي فقط (Audio-Only Alert):** يتم إصدار التنبيه صوتياً فقط (عند $100 \text{ m}$ ثم $25 \text{ m}$) مع إخفاء شريط التحذير البصري تماماً لعدم إزعاج السائق.

---

### 2. معادلات قياس ومعدل استهلاك الوقود متعدد الأنواع (Multi-Fuel Consumption & ECU Auto-Detection)
يقوم التطبيق بقراءة نوع الوقود **تلقائياً من كمبيوتر السيارة عبر الكود القياسي `OBD-II Mode 01 PID 0151`** (أو يتيح اختياره يدوياً عند تسجيل السيارة: بنزين / ديزل-سولار / غاز طبيعي CNG)، ويضبط الثوابت الفيزيائية والكيميائية كالتالي:

| نوع الوقود | الكثافة القياسية ($\rho_{fuel}$) | النسبة الستوكيومترية ($\text{AFR}_{stoich}$) | معامل التحويل ($\text{AFR} \times \rho$) |
| :--- | :---: | :---: | :---: |
| **بنزين (Gasoline)** | $740 \text{ g/L}$ | $14.7 : 1$ | $10,878 \text{ g air / L fuel}$ |
| **ديزل / سولار (Diesel)** | $840 \text{ g/L}$ | $22.0 : 1$ | $18,480 \text{ g air / L fuel}$ |
| **غاز طبيعي (CNG / LPG)** | $720 \text{ g/m}^3$ | $17.2 : 1$ | $12,384 \text{ g air / m}^3\text{ gas}$ |

#### أ. الطريقة المباشرة (PID 015E - Direct Fuel Rate)
$$Q_{fuel} = \frac{256 \cdot A + B}{20} \quad [\text{L/h}]$$

#### ب. طريقة حساس تدفق الهواء الكتلي (MAF - Mass Air Flow PID 0110) مع تصحيح Fuel Trims
$$\text{MAF [g/s]} = \frac{256 \cdot A + B}{100}$$
$$\text{Trim Factor} = \text{clamp}\left(1 + \frac{\text{STFT} + \text{LTFT}}{100},\; 0.75,\; 1.35\right)$$
$$Q_{fuel} = \frac{\text{MAF [g/s]} \times 3600}{\text{AFR}_{stoich} \times \rho_{fuel}} \times \text{Trim Factor} \quad [\text{L/h أو m}^3\text{/h}]$$

#### ج. طريقة السرعة والكثافة الرياضية البديلة (Speed-Density Method)
تُستخدم عندما لا تحتوي المركبة على حساس MAF، وتعتمد على حساس الضغط المطلق لمجمع السحب (MAP PID 010B) وسرعة دوران المحرك (RPM PID 010C) وحرارة هواء السحب (IAT PID 010F):
$$T_K = IAT [^\circ\text{C}] + 273.15$$
$$\text{Air Mass Flow [g/s]} = \frac{V_d \times \text{VE} \times \text{RPM} \times \text{MAP [kPa]} \times 28.97}{120 \times R_{gas} \times T_K}$$
حيث:
- $V_d$: سعة المحرك باللترات (Displacement Volume).
- $\text{VE}$: الكفاءة الحجمية المقدرة للمحرك (Volumetric Efficiency $\approx 0.80 - 0.85$).
- $R_{gas}$: ثابت الغازات العام ($8.314 \text{ J/(mol}\cdot\text{K)}$).

---

### 3. تقييم كفاءة البطارية ودينامو الشحن (Battery SOC & Alternator Ripple)
- **جهد السكون والبطارية (Key-Off / Pre-Crank Voltage):**
  - $V_{batt} \ge 12.6\text{V} \implies \text{SOC} \approx 100\%$
  - $V_{batt} \approx 12.4\text{V} \implies \text{SOC} \approx 75\%$
  - $V_{batt} \approx 12.0\text{V} \implies \text{SOC} \approx 25\%$
  - $V_{batt} < 11.8\text{V} \implies \text{تنبيه حرج بضعف شحن البطارية}$
- **نظام الشحن أثناء الدوران (Running Alternator Voltage):**
  - المدى السليم: $13.6\text{V} \le V_{alt} \le 14.6\text{V}$
  - تنبيه شحن زائد (Overcharge / Faulty Regulator): $V_{alt} > 15.0\text{V}$
  - تنبيه قصور دينامو (Charging Under-Voltage): $V_{alt} < 13.0\text{V}$

---

### 4. التخزين الذكي التفاضلي للبيانات (Smart Telemetry Delta-Compression)
لتوفير استهلاك باقة الإنترنت وتخزين البيانات اللحظية بكفاءة:
- يتم تسجيل السجل اللحظي فقط عند حدوث **تغير ذو دلالة إحصائية (Significant Delta)**:
  - $\Delta v \ge 5.0 \text{ km/h}$
  - $\Delta \text{RPM} \ge 150 \text{ RPM}$
  - $\Delta T_{coolant} \ge 2.0 ^\circ\text{C}$
  - $\Delta V_{batt} \ge 0.3 \text{ V}$
  - ظهور كود عطل جديد (New DTC Injected).
- تُخزن القراءات كأرقام خام وحقول قياسية مجردة (Raw Values) دون نصوص حشو إنشائية.
