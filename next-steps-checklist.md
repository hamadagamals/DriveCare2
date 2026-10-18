# Next professional steps

## Phase 1: complete owner app
- [x] Dashboard
- [x] Trips
- [x] Diagnostics
- [x] Maintenance
- [x] Profile
- [x] Settings
- [x] Health score
- [x] API routes
- [x] Stored schema

## Phase 2: secure production readiness
- [ ] Add auth flow
- [ ] Add multi-vehicle support
- [ ] Add cloud sync
- [ ] Add push notifications
- [ ] Add backup/export endpoint
- [ ] Add local-first storage layer
- [ ] Add onboarding flow
- [ ] **[ملاحظة معتمدة للتنفيذ القادم]:** دمج فحص الـ OBD بإدارة المركبات تلقائياً — بمجرد الاقتران يتم استدعاء الـ VIN عبر كود 0902 والتعرف التلقائي على موديل السيارة وربطها بالعدادات دون إدخال يدوي.
- [ ] **[ملاحظة معتمدة للمرحلة الثانية]:** ترحيل خاصية "مسح وتصفير أكواد الأعطال (Clear / Reset DTCs)" للمرحلة الثانية، مع وضع شروط أمان كاملة (توقف السيارة التام، وتأكيد المستخدم)، والتركيز في المرحلة الحالية فقط على القراءة والكشف والتنبيه الفوري بأمان 100%.
- [ ] **[ملاحظة معتمدة للنسخة القادمة - حساب استهلاك الوقود المزدوج والمعايرة]:** إضافة خيار حساب استهلاك الوقود عبر "معدل انخفاض منسوب تانك البنزين (Fuel Tank Level Delta)" ومطابقته مع قراءة تدفق الهواء اللحظي (MAF Sensor Fusion) لمعايرة وتدقيق الاستهلاك الفعلي في المسافات الطويلة وتفويالت التانك الكاملة، مع تطبيق فلتر استقرار الوقود على أرض مستوية (Stationary Level Filter) وإدخال سعة التانك الإجمالية باللتر.


## Phase 3: garage/admin app
- [ ] Create garage dashboard API
- [ ] Add repair notes and service reports
- [ ] Add user/vehicle management tools
- [ ] Add historical service timeline
- [ ] Add report export to PDF/CSV

## Phase 4: scale and polish
- [ ] Convert to Flutter or React Native
- [ ] Add OBD streaming integration
- [ ] Add predictive maintenance model
- [ ] Add AI suggestions engine
- [ ] Add analytics dashboard
