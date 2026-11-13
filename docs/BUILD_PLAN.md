# خطة بناء التطبيق بـ Claude Code

هذا الملف إلك انت. فيه كيف تجهّز كل إشي مرة وحدة، والسكلات، وفريق الوكلاء، وكيف تشغّل كل مرحلة،
والبرومبت الجاهز لكل مرحلة. البرومبتات مكتوبة بالإنجليزي لأنها موجّهة لـ Claude Code.
الهيكل التقني بـ `docs/ARCHITECTURE.md`، وتفاصيل فريق الوكلاء بـ `docs/AI_TEAM.md`.
الكاميرا والموديل وفك مخرجاته جايين من الكود الرسمي لـ Ultralytics (ترخيص AGPL-3.0)، وإحنا بنبني فوقه
التتبع والنعومة والقفل والتشخيص والقياس والاختبارات.

---

## 0. الصورة العامة: مين بيعمل شو

| مين | دوره |
|---|---|
| **lead — Opus 5.5 (xhigh، وmax لما تطلبه)** | المخطط والمشرف: بيكتب خطة كل مرحلة، بيوزّع المهام، بيفحص النتائج، بيفتح الـ PR. ما بيكتب كود |
| **implementer — Sonnet 5.5** | بينفّذ المهام وحدة وحدة: اختبارات أول، بعدين كود |
| **android-reviewer و debugger — Opus 5.5** | مراجعة مستقلة بآخر كل مرحلة، وحل المشاكل الصعبة لما تتكرر |
| **consultant — Fable 5.2** | المستشار: لما الفريق ما يعرف إشي، أو قرار خطير، أو مشكلة ما انحلت |
| **GitHub Actions** | الحَكَم النهائي: بيبني الـ APK ويشغّل كل الاختبارات مع كل تعديل |
| **رابط `latest` على GitHub** | منه بتنزّل آخر APK على تلفونك |
| **تلفونك (Poco X7 Pro)** | التجربة الحقيقية، وزر "مشاركة التشخيص" لما يصير خطأ |
| **اللابتوب** | ما بنستعمله للبناء. بناء أندرويد بده رام أكثر بكثير من 4 جيجا |

كل الشغل بيصير بـ **Claude Code على الويب** (claude.ai/code، أو من تطبيق Claude على التلفون).

---

## 1. التجهيز (مرة وحدة، حوالي 20 دقيقة)

### 1.1 الـ repo
اعمل `pose-tracker-android` كـ **Private** على حسابك، وارفع الملفات الثلاثة (ARCHITECTURE.md وAI_TEAM.md
وBUILD_PLAN.md) لمجلد `docs/` بالـ repo.

### 1.2 ربط Claude Code بـ GitHub
1. افتح **claude.ai/code**.
2. اربط حساب GitHub تبعك إذا مش مربوط، واسمح بالوصول للـ repo الجديد.

### 1.3 بيئة سحابية اسمها `android`
من قائمة البيئات تحت مربع الرسالة، اعمل بيئة جديدة:

1. **Name:** `android`
2. **Network access:** اختار **Custom**، وبخانة Allowed domains اكتب:
   ```
   dl.google.com
   ```
   وعلّم على **Also include default list of common package managers**.
   السبب: مكتبات أندرويد من جوجل والـ Android SDK بينزلوا من `dl.google.com`، وهذا مش موجود بالقائمة الافتراضية.
3. **Setup script:** خليه فاضي هلّق. بالمرحلة M0a، Claude بيكتب السكربت ويجرّبه، وبعدها بيقلك شو تلصق هون بالضبط.
4. **Environment variables:** لا تحط `CLAUDE_CODE_EFFORT_LEVEL`. لو انحط، بيلغي مستوى التفكير الخاص بكل وكيل.

### 1.4 Context7
Context7 بيعطي Claude توثيق محدّث لـ CameraX وLiteRT بدل ما يعتمد على ذاكرته. تأكد إنه مفعّل بالجلسات،
وسكّر الموصلات اللي ما إلها علاقة بالبرمجة (Canva وAdobe وNotion وغيرهم) بجلسات المشروع.

### 1.5 التلفون
أول مرة بتفتح فيها ملف APK من المتصفح، أندرويد رح يطلب منك تسمح للمتصفح بتثبيت التطبيقات. وافق.

### 1.6 الموديل ومستوى التفكير
- **بأول جلسة (M0a):** الإعدادات لسا مش موجودة، فاختار **Opus 5.5** من قائمة الموديلات بنفسك.
- **من M0b وطالع:** ملف `.claude/settings.json` بيخلي كل جلسة تبلّش على Opus 5.5 بمستوى `xhigh` لحالها.
- **إذا بدك max:** اكتب `/effort max` بأول الجلسة، قبل البرومبت (إذا الأمر متاح بالجلسة). الإعدادات ما
  بتقبل تحفظ `max` بشكل دائم، فلازم تكتبه بكل جلسة بدك فيها max.

---

## 2. السكلات وفريق الوكلاء

**القاعدة:** عدد قليل من مصادر موثوقة. السكلات الكثيرة بتلخبط الوكيل وبتاكل من مساحة تفكيره،
وكمان السكلات عبارة عن تعليمات الوكيل بينفذها، فلازم نثق بمصدرها.

**طريقة التركيب:** Claude بينسخ السكلات المختارة لجوّا الـ repo بمجلد `.claude/skills/`، مع ملف الرخصة
ورقم النسخة (commit). هذا مضمون يشتغل بالجلسات السحابية. أما تركيب الـ plugins من مصادر خارجية بالجلسات
السحابية فمش مضمون حالياً. وفايدة إضافية: النسخة بتضل ثابتة وما بتتغير لحالها.

### أ) سكلات جوجل الرسمية لأندرويد — `android/skills` (رخصة Apache-2.0)
| السكل | ليش بنحتاجها |
|---|---|
| **CameraX** | أفضل الممارسات للكاميرا: الدقة، الدوران، تحليل الصور |
| **Run on-device models with LiteRT** | تشغيل الموديل على الـ GPU بالطريقة الرسمية الجديدة (CompiledModel) |
| **Set up testing strategy** | تنظيم الاختبارات |
| **Optimize Compose performance** | عشان رسم النقطة 120 مرة بالثانية ما يعمل تقطيع |
| **Audit and fix ANRs** | يمنع تعليق التطبيق |
| **Make app edge-to-edge** | أندرويد 15 بيفرض ملء الشاشة، وهذا بيأثر على مكان النقطة فوق الكاميرا |

ملاحظة: سكل LiteRT الرسمية بتفضّل نسخة Play services. إحنا اخترنا النسخة المدمجة بالتطبيق، عشان النتائج
تكون ثابتة على كل جهاز، ومكتوب بـ AGENTS.md إن هذا قرار مقصود.

### ب) سكلات Chris Banes لـ Kotlin وCompose — `chrisbanes/skills` (Apache-2.0)
من مهندسي فريق أندرويد. بناخذ منها بس اللي بيخصّنا:
- قراءة الحالة بمرحلة الرسم بدل إعادة بناء الواجهة (deferred reads). هذا بالضبط اللي بيخلي النقطة ناعمة.
- الـ side effects بـ Compose.
- الـ coroutines والـ Flow.

### ج) سكلات طريقة الشغل — `addyosmani/agent-skills` (رخصة MIT)
| السكل | شو بتعمل |
|---|---|
| `incremental-implementation` | بيبني بخطوات صغيرة، كل خطوة مختبرة |
| `test-driven-development` | بيكتب الاختبار قبل الكود |
| `debugging-and-error-recovery` | خطوات منظمة لحل الأخطاء بدل التخمين |
| `source-driven-development` | بيرجع للتوثيق الرسمي بدل ما يخترع APIs |
| `code-review-and-quality` | مراجعة قبل الدمج |
| `doubt-driven-development` | مراجعة مستقلة بسياق جديد للقرارات المهمة |
| `ci-cd-and-automation` | للمرحلة M0 بس (إعداد GitHub Actions) |

**بديل:** `obra/superpowers` (MIT) قوية كمان. بس **لا تركّب الاثنين مع بعض**، لأنهم بيتعارضوا.

### د) فريق الوكلاء (التفاصيل الكاملة بـ `docs/AI_TEAM.md`)
| الوكيل | الموديل | مستوى التفكير | دوره |
|---|---|---|---|
| **lead** (الجلسة الرئيسية) | Opus 5.5 | xhigh، وmax لما تطلبه | بيخطط، بيوزّع، بيفحص، بيراجع، بيسلّم. ما بيكتب كود |
| **implementer** | Sonnet 5.5 | high | بينفذ مهمة وحدة من الخطة وبيرجع تقرير قصير |
| **android-reviewer** | Opus 5.5 | high | مراجعة مستقلة بسياق جديد بآخر كل مرحلة. بيقرأ بس |
| **debugger** | Opus 5.5 | high | بس لما مهمة تفشل مرتين، أو CI أحمر مرتين، أو بلاغ خطأ من التلفون |
| **consultant** | Fable 5.2 | xhigh | المستشار: سؤال واحد محدد لما الفريق ما يعرف، أو التوثيق متضارب، أو قرار خطير (التتبع، الإحداثيات، التأخير)، أو مشكلة ما حلها الـ debugger. بيقرأ بس |
| **Explore** | Haiku 5.5 | — | بحث سريع ورخيص بالملفات (بدل النسخة الأصلية اللي كانت رح تشتغل على Opus) |

ومعهم سكربتين بدون ذكاء اصطناعي: `verify.sh` للبناء والاختبارات، و`ci-status.sh` لنتيجة GitHub Actions.
بيطبعوا ملخص قصير، فما في وكيل بيقرأ آلاف الأسطر من السجلات.

**ليش هيك:**
- التخطيط والإشراف والمراجعة هم اللي بيحددوا الجودة، فإلهم أقوى موديل بأعلى مستوى تفكير.
- كتابة الكود هي أغلب الاستهلاك، فعلى Sonnet.
- كل وكيل فرعي بيبلّش بسياق نظيف، وبيرجع للـ lead ملخص بس، فجلسة الـ lead بتضل صغيرة طول المرحلة.

**ليش الـ lead هو الجلسة الرئيسية نفسها، مش ملف وكيل منفصل:** لو شغّلنا الجلسة الرئيسية كوكيل، تعليمات
Claude Code الأساسية (git والـ PR واستعمال الأدوات) بتنشال وبتنحط مكانها تعليمات الوكيل بس. والـ lead هو
اللي بيرفع الفروع وبيفتح الـ PR، فبيضل محتفظ بالتعليمات الأساسية، وبياخذ دوره من `CLAUDE.md`.

**ترتيب حل المشاكل:** الـ implementer (محاولتين) ← الـ debugger ← المستشار Fable ← انت.

**Fable والتكلفة:** Fable أغلى من Opus، فإله حد: لحد 3 استشارات بكل مرحلة بدون ما يسألك، وأكثر من هيك
بيستأذن. وكل استشارة بتنكتب بملف الخطة. إذا اشتراكك Max، Fable داخل بالاشتراك (لحد نص الحد الأسبوعي)؛
وإذا Pro، كل استشارة بتنحسب من الرصيد الإضافي المدفوع. وإذا Fable مش متاح، الـ lead بيعيد نفس السؤال على
Opus وبيخبرك.

**صراحة:** Opus على max بيستهلك من حد الاستخدام أسرع. إذا لاحظت إنه بيخلص بسرعة، لا تكتب `/effort max`،
والجلسة بتشتغل على `xhigh` المحفوظ.

---

## 3. طريقة الشغل بكل مرحلة

1. افتح **جلسة جديدة** على claude.ai/code، واختار الـ repo والبيئة `android`.
2. الصق برومبت المرحلة من القسم 4.
3. الـ lead بيكتب **خطة** بـ `docs/plans/M#.md` وبيعرضها عليك. راجعها، واطلب تعديل أو قله `approved`.
4. بيوزّع المهام وحدة وحدة على الـ implementer، وبيفحص كل وحدة. هون ما بتحتاج تتدخل.
5. بالآخر: مراجعة مستقلة من الـ android-reviewer، وبعدها PR وCI أخضر.
6. بيكتب **ملف التسليم** `docs/progress/M#.md`، وبيقلك إنه جاهز.
7. **انت بتعمل Merge** للـ PR من GitHub.
8. نزّل الـ APK من رابط **`latest`** وجرّب حسب `docs/TEST_CHECKLIST.md`.
9. إذا كله تمام، قله: `tag this build as ok`. وإذا في مشكلة، استعمل القسم 5.

**قواعد إلك:**
- مرحلة وحدة بكل جلسة. الجلسة الجديدة بتبلّش بذهن صافي وبتغلط أقل.
- **ما توافق أبداً** على تعطيل اختبار أو تخفيف شرطه عشان يمرّ البناء. إذا طلب هيك، قله: `No. Find the root cause.`
- لا تدمج PR فيه CI أحمر.
- لا تطلب تعديل AGENTS.md أو CLAUDE.md بنص الجلسة، لأنه بيخرّب الكاش. خليها لآخر الجلسة.
- إذا بدك توقف أكثر من ساعة (مثلاً خلص حد الاستخدام)، قله أول: `Write the handoff now.` وبعدين كمّل بجلسة جديدة بدل ما ترجع للجلسة الكبيرة.

---

## 4. البرومبتات

### M0a — التأسيس (الأدوات وفريق الوكلاء، بدون ميزات)
اختار Opus 5.5 قبلها (القسم 1.6).
```
Read docs/ARCHITECTURE.md, docs/AI_TEAM.md and docs/BUILD_PLAN.md. This is milestone M0a (bootstrap):
tooling only, no app features. The AI team does not exist yet, so in this session you act as the lead
and may write files directly. First show me a numbered plan and wait for "approved".

1. Cloud environment: write scripts/cloud-setup.sh that installs the Android SDK (cmdline-tools,
   platform-tools, the platform and build-tools matching our compileSdk) under $HOME/android-sdk,
   accepts licences and exports ANDROID_HOME. Look up the current cmdline-tools download on
   dl.google.com instead of guessing a file name. If Gradle cannot reach the network through this
   environment's HTTPS proxy, configure the JVM proxy in ~/.gradle/gradle.properties from $HTTPS_PROXY.
   It must finish in under 5 minutes. Run it here, prove ./gradlew --version works, then tell me
   exactly what to paste into the environment's "Setup script" field.
2. AI team: create .claude/settings.json, CLAUDE.md and every file in .claude/agents/ exactly as written
   in docs/AI_TEAM.md §4-§5. Create scripts/verify.sh and scripts/ci-status.sh per §6 and test both.
   Create docs/REPO_MAP.md (short map of modules and entry points), docs/plans/ and docs/progress/.
3. Skills: vendor into .claude/skills/ (copy each skill folder with its LICENSE; record source repo and
   commit SHA in .claude/skills/SOURCES.md):
   - from android/skills: CameraX; Run on-device models with LiteRT; Set up testing strategy;
     Optimize Compose performance; Audit and fix ANRs; Make app edge-to-edge
   - from chrisbanes/skills: the skills covering deferred state reads in the draw phase,
     Compose side effects, and coroutines/Flow (list the repo first and tell me which you picked)
   - from addyosmani/agent-skills: incremental-implementation, test-driven-development,
     debugging-and-error-recovery, source-driven-development, code-review-and-quality,
     doubt-driven-development, ci-cd-and-automation
   If cloning a public repo is blocked in this session, stop and tell me which repo to add.
4. Project skeleton per ARCHITECTURE.md §3: core (kotlin jvm), detector (android library),
   app (android application, Compose). Version catalog with pinned current stable versions
   (verify each from official sources / Context7 and list what you chose). minSdk 26.
5. AGENTS.md from ARCHITECTURE.md §11 (under ~150 lines). CLAUDE.md is exactly the one in AI_TEAM.md §5.
6. CI per ARCHITECTURE.md §10: android.yml (spotlessCheck test lint assembleDebug, @Test count guard,
   APK artifact, rolling "latest" release on main), signing with a committed signing/debug.keystore
   (generate it with keytool, debug-only).
7. One placeholder screen and one passing test per module, to prove the pipeline.
8. Model export (ARCHITECTURE.md §8): tools/export_model.py, pinned tools/requirements.txt and
   .github/workflows/export-model.yml (workflow_dispatch, ubuntu x86_64) that exports yolo26n-pose with
   format="litert", nms=False at imgsz 320 and 416; runs Python predict() on a committed licence-free fixture
   image with 2+ people and saves golden outputs; writes model/model_info.json (I/O shapes and dtypes,
   ultralytics version, sha256 per file); commits the pip freeze as tools/requirements.lock; opens a PR.
   A workflow_dispatch workflow can only be triggered once it is on the default branch, so: after I merge
   the M0a PR, trigger it, review the PR it opens and report the exact input/output tensor shapes. If I
   haven't merged yet when you finish, write this as the first open item in docs/progress/M0a.md.
Verify with scripts/verify.sh, push a branch, open a PR, check CI with scripts/ci-status.sh and fix until
green. Then write docs/progress/M0a.md and stop. Tell me: files created, versions chosen, the Setup script
to paste, the model-export PR status, and anything you could not do.
```

### M0b — استيراد كود Ultralytics وأول نقاط على الناس
من هون وطالع، كل برومبت قصير، والـ lead بيمشي على خطوات `docs/AI_TEAM.md` لحاله.
```
Milestone M0b — import the Ultralytics Android core and show raw pose keypoints.
First confirm you are the main session acting as lead on Opus 5.5, and that the subagents implementer,
android-reviewer, debugger, consultant and Explore are available; if not, tell me and stop.
Architecture: §2.1, §3, §6.1, §6.7, §10. Skills to use: CameraX, Run on-device models with LiteRT,
Make app edge-to-edge.
Before anything else: if model/ does not yet contain the exported models (model-export PR from M0a not
merged), finish that first and ask me to merge it.
Scope: clone github.com/ultralytics/yolo-flutter-app at the pinned commit in §2.1 (read-only, outside the repo);
copy ONLY the "keep" files into detector/ and app/ as §2.1 says, keep their headers and package, drop
everything listed under "drop"; write third_party/ULTRALYTICS.md (commit, kept/dropped files, change log)
and add the AGPL-3.0 LICENSE at the root; wire the camera view into our app with our model from model/
(320); draw raw boxes + keypoints in green; camera permission flow (incl. denied state); front/back switch;
HUD with version + git SHA + accelerator, and the red "Running on CPU" banner; diagnostics per §6.7;
docs/TEST_CHECKLIST.md with an M0 section. Follow your workflow.
```

### M1 — القياس والسرعة
```
Milestone M1 — threading fix, golden test, frame rate and benchmark.
Architecture: §5, §6.1, §6.8. Skills to use: Run on-device models with LiteRT, CameraX, source-driven-development.
Scope: fix the upstream threading so the CompiledModel is created, warmed up, run and closed on the single
analysis thread (§2.1 known issue), buffers allocated once; golden decode tests for both model sizes against
the Python outputs in model/; frame-rate selection via CameraInfo.getSupportedFrameRateRanges + the
SessionConfig frame-rate API (prefer [60,60], then [30,60], then [30,30]); the Benchmark screen with CSV
export and 10-minute soak mode; both added to Share diagnostics; TEST_CHECKLIST.md (M1). Follow your workflow.
```

### M2 — دقة مكان النقطة
```
Milestone M2 — Img→View mapping.
Architecture: §4. Skills to use: test-driven-development, source-driven-development.
Scope: start from the mapping in the imported upstream camera/overlay code and move it into a pure-Kotlin
Matrix3 in core, exactly as §4.2 (official CameraX "Transform output" recipe, shared ViewPort, front-camera
mirror); log the vendored-code change; table tests for 4 rotations × mirror × aspect ratios ×
sensorOrientation 90/270; a test that our matrix equals the official recipe; TEST_CHECKLIST.md (M2: green
dots exactly on heads, back and front camera, edge-to-edge). Follow your workflow.
```

### M3 — التتبع
```
Milestone M3 — tracking.
Architecture: §6.2, §6.3, §9. Skills to use: test-driven-development, doubt-driven-development.
Scope: synthetic truth tests and metrics FIRST (crossing, stop/start, 0.5 s dropout, keypoint dropout,
variable dt; at 30 and 60 fps; thresholds from §9) and show me they fail; then HeadPointEstimator, Kalman CV,
ByteTrack two-stage association with Hungarian behind Assigner, and the time-based lifecycle until they pass;
wire into the app as red dots with track IDs. Run a doubt-driven review of the tracker design before the PR.
Follow your workflow.
```

### M4 — النعومة والقفل
```
Milestone M4 — smoothing, lock and debug sheet.
Architecture: §6.4, §6.5, §6.6, §7.1. Skills to use: Optimize Compose performance, the Chris Banes
deferred-reads skill.
Scope: render-side prediction (now − previewLatencyMs, bounded lookahead, velocity confidence), OneEuro per
track, fade in/out, tap-to-lock with re-lock rules, debug sheet (sliders for the main TrackerConfig values,
model size, target fps, accelerator override, reset); overlay reads frame time in the draw phase only;
TEST_CHECKLIST.md (M4). Follow your workflow.
```

### M5 — الحرارة واللمسات الأخيرة
```
Milestone M5 — thermal policy and polish.
Architecture: §7.2. Skills to use: Audit and fix ANRs, Optimize Compose performance.
Scope: thermal policy (§7.2) with HUD + log entries; perf pass (no per-frame allocations, checked with the
benchmark soak); lifecycle polish (rotation, background, camera switch); replay mode in the debug sheet;
TEST_CHECKLIST.md (M5 + 30-minute run). Follow your workflow.
```

---

## 5. لما يصير خطأ

1. بالتطبيق اضغط **Share diagnostics** واحفظ الملف.
2. افتح جلسة (نفس الجلسة إذا لسا مفتوحة)، ارفع الملف، واكتب:
```
Bug report.
What I did: <الخطوات>
What I expected: <المتوقع>
What happened: <اللي صار>
Build: <رقم النسخة من الـ HUD>
Diagnostics zip attached. Send it to the debugger: reproduce from the replay or logs, find the root
cause, add a regression test that fails first, then fix. Show me the plan first.
```

---

## 6. تقدير الوقت والتكلفة (تقريبي)

- **عدد الجلسات:** حوالي 7 إلى 8 (مرحلة بكل جلسة، وM4 ممكن بدها جلستين عشان الضبط على التلفون). أخف من قبل، لأن الكاميرا والموديل جايين جاهزين من كود Ultralytics.
- **وقت الجلسة:** بين نص ساعة وساعة ونص من شغل Claude، غير وقت تجربتك انت.
- **المدة الكلية:** أسبوع إلى أسبوعين، حسب وقتك بالتجربة.
- **حدود الاستخدام:** الجلسات الطويلة بتستهلك كثير. إذا وصلت للحد، كمّل بعدين من نفس المرحلة، لأن الشغل محفوظ بالـ PR.
- **GitHub Actions:** الحد المجاني للـ repo الخاص حوالي 2000 دقيقة بالشهر، يعني تقريباً 200 بناء. هذا كافي إذا التزمنا بالقواعد.
