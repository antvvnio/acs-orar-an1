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
