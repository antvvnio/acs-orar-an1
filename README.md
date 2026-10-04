# ACS Orar — Anul I ACS UPB

Aplicație Android pentru **anul I ACS / UPB** care transformă fișierele Excel oficiale de orar într-un orar lizibil pentru grupa studentului.

## Serii suportate

Aplicația este intenționat limitată la anul I și a fost verificată pe toate formatele furnizate:

- AA
- AB
- AC
- CA
- CB
- CC
- CD

Dacă este încărcat un orar care nu arată ca un orar de anul I din aceste serii, aplicația îl refuză în loc să încerce să ghicească structura.

## Flux

1. Apeși **Alege fișier Excel** și alegi `.xls` / `.xlsx`.
2. Aplicația detectează seria și grupele din fișier.
3. Introduci doar numărul grupei, de exemplu `313`.
4. Dacă seria are opționale, aplicația afișează numai codurile care apar efectiv în grila acelei serii.
5. Introduci codul tău, de exemplu `IA1`, `GAC`, `TC`, `IF`, `Ant`, `Log`, `IFR`, `IFC` etc.
6. Apeși **Arată orarul**.
7. Vezi doar activitățile grupei tale și doar cele valabile în săptămâna curentă.

Grupa și codul/codurile de opțional folosite ultima dată sunt memorate local.

## Ce afișează

Pentru fiecare activitate:

- ziua;
- intervalul (`08:00 – 10:00` etc.);
- denumirea completă a disciplinei;
- prescurtarea în paranteză, când poate fi determinată (`ISO`, `USO`, `AMat`, `ALGAED`, `PCLP`, `PL` etc.);
- tipul activității: `(curs)`, `(laborator)`, `(seminar)`;
- sala;
- subgrupa, când cele două subgrupe au activități diferite.

Exemplu:

`Introducere în sisteme de operare (ISO) (laborator)`  
`Sala: ED310a`

Parserul evită să confunde săli precum `EC 105` sau `AN 030` cu prescurtări de materii. Dacă în celulă apare numai denumirea completă, încearcă să o asocieze cu prescurtarea cunoscută din legenda aceluiași workbook.

## Opționale

Fișierele de anul I nu folosesc același format peste tot. Parserul tratează separat aceste cazuri:

- AA / AC: cursurile opționale pot fi celule comune peste toate grupele;
- AB: un curs opțional poate fi scris numai în câteva coloane ale grilei, deși ora cursului este comună pentru cei care l-au ales;
- CA / CB / CC / CD: legenda poate conține mai multe discipline decât cele disponibile efectiv în seria respectivă.

De aceea lista de coduri oferită utilizatorului este construită din **grila seriei**, nu din toate intrările din legendă.

Pentru cursurile opționale, aplicația poate folosi ora găsită oriunde în grila acelei serii. Pentru seminare/laboratoare, nu copiază automat o grupă străină: dacă legenda are o singură variantă clară, o poate afișa; dacă are mai multe variante fără repartizare pe grupa studentului, nu alege una la întâmplare.

Dacă un cod apare ca opțional în grilă, dar fișierul nu conține suficient program pentru el (de exemplu zi/oră/sală lipsă), aplicația afișează un mesaj în loc să inventeze programul.

## Par / impar

Pentru anul universitar 2026–2027:

- **28 septembrie – 2 octombrie 2026 = săptămână IMPARĂ**;
- săptămâna următoare = PARĂ;
- apoi alternează automat.

În unele fișiere ACS un interval este împărțit pe două rânduri fizice. Parserul diferențiază între:

- activitate doar pe partea de sus → impar;
- activitate doar pe partea de jos → par;
- materia pe un rând + sala pe rândul următor → aceeași activitate, nu două săptămâni diferite.

Orele din paritatea opusă nu sunt afișate.

## Implementare

- `.xls`: JExcelAPI (`jxl 2.6.12`);
- `.xlsx`: reader local pentru XML-ul necesar din arhivă;
- `SubjectCatalogParser.kt`: coduri + denumiri complete și filtrarea codurilor false de tip sală;
- `ScheduleParser.kt`: grupe, intervale, subgrupe, paritate și opționalele disponibile în serie;
- `OptionalScheduleParser.kt`: programul opționalelor descris în legendă, fără a ghici între sesiuni alternative;
- `ActivityTextParser.kt`: materie, prescurtare, tip și sală.

## Build

- Android Studio recent;
- JDK 17+;
- Android SDK 36;
- Gradle 8.14.x.

Există workflow-uri GitHub Actions pentru build-ul APK-ului și publicarea release-urilor.


## v1.1
- Correct system-bar/display-cutout insets for Android 15/16 edge-to-edge.
- Opens on the current weekday; Saturday/Sunday default to Monday.
- Shows the current day/date in the week header.

## v1.2

- Adaptive launcher icon based on the supplied ACS Orar artwork, including Android 13+ monochrome themed icon.
- Configurable local class reminders (0-120 minutes before, 5 minute increments), notification permission handling, reboot/time-change rescheduling, and direct navigation to the relevant weekday.
- Reminders respect the official 2026-2027 Bucharest teaching blocks for semester I and skip 30 November / 1 December.
- Campus map screen for the official UPB PDF (Noul Local, Leu, Polizu), cached locally after first download, with pinch-to-zoom, pan, double-tap zoom and reset.
- About / credits screen with `made by antvvnio` and https://github.com/antvvnio.


## Parser border-aware (v1.3)

Versiunea 1.3 păstrează și interpretează bordurile celulelor din fișierele `.xls`. În orarele ACS, lipsa unei borduri orizontale între două rânduri de câte o oră poate reprezenta un singur bloc vizual de două ore; activitatea din jumătatea superioară este tratată ca săptămână impară, iar cea din jumătatea inferioară ca săptămână pară atunci când structura chenarului indică acest lucru. Bordurile verticale sunt folosite și pentru a determina dacă o activitate aparține unei subgrupe sau întregii grupe.


## Navigare pe săptămâni

ACS Orar permite vizualizarea a maximum două săptămâni de predare înapoi sau înainte față de săptămâna curentă. Navigarea este limitată la săptămânile de curs din semestrul I 2026–2027: 28 septembrie–18 decembrie 2026 și 11–22 ianuarie 2027. Vacanța de iarnă este sărită automat, iar paritatea continuă după numărul săptămânii de predare (13/14 în ianuarie), nu după numărul de săptămâni calendaristice.


## v1.9.1

- Corectează detectarea orei pentru activitățile care încep la jumătatea unui interval fizic din Excel și continuă în intervalul următor.
- Cazul concret corectat: **AA / miercuri / ISO = 14:00–16:00**.
- Regula parserului este generică și nu este hardcodată pe seria AA sau pe disciplina ISO.
