# ACS Orar - Anul I ACS UPB

Aplicație Android pentru **anul I ACS / UPB** care folosește orarele oficiale incluse în aplicație și afișează programul relevant pentru seria, grupa, subgrupa și opționalele studentului.

## Serii suportate

Aplicația este intenționat limitată la anul I și a fost verificată pe toate formatele furnizate:
- AIASI: AA, AB, AC
- CTI: CA, CB, CC, CD

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

## Subgrupe

Subgrupa este o setare globală pentru grupa selectată. Dacă grupa are mai multe subgrupe, înainte de secțiunea de opționale apare un singur selector:

- **Nu știu / Arată ambele** — implicit, păstrează activitățile tuturor subgrupelor;
- **Subgrupa 1**;
- **Subgrupa 2**;
- sau alte valori dacă grupa are mai multe subgrupe în orarul oficial.

Activitățile comune întregii grupe rămân vizibile indiferent de alegere. Activitățile marcate pentru altă subgrupă sunt ascunse, iar notificările folosesc aceeași selecție globală.

Exemplu: la **311 AB**, marți 18–20, IA1 este în coloana SG1, iar ISO este în coloana SG2 în săptămâna pară. Alegerea globală a subgrupei filtrează aceste activități exact după poziția lor din orar.

## Opționale CTI (seriile C)

Pentru seriile CA, CB, CC și CD, aplicația recunoaște disciplinele din legenda oficială, inclusiv:

- Antropologie (Ant);
- Logică (Log);
- Tehnici de comunicare (TC);
- Istoria filosofiei (IF);
- Istoria și filosofia religiilor (IFR);
- Istoria dezvoltării științei și tehnicii (IDST).

Codul `IFC` întâlnit în grila seriei CC este tratat ca alias pentru `IFR`, nu ca o disciplină separată.

Pentru disciplinele unde legenda spune că seminarul **se stabilește la curs**, aplicația permite configurarea manuală a seminarului după ce studentul află repartizarea: zi, interval, săptămână pară/impară și sală. Seminarul configurat este folosit și pentru notificări.

În seriile AA, AB și AC, nota oficială mai precizează că laboratoarele opționale de **IA2** și **GAC** se stabilesc la curs. Dacă studentul selectează una dintre aceste discipline, poate adăuga manual laboratorul în același mod.

## Facultative

În toate seriile scanate apare **Psihologia educației** ca disciplină facultativă, cu două intervale de curs menționate în notă:

- luni 12:00–14:00, CantiCTI;
- joi 10:00–12:00, A04 Leu.

Ambele intervale sunt tratate ca ore de curs ale disciplinei. Seminarul de Psihologia educației se stabilește la curs și poate fi adăugat manual după ce este comunicat.

Tot în notă apare **Franceză — seminar facultativ**, cu program stabilit cu profesorul. Poate fi selectată și configurată manual în aplicație.

Pentru seriile AA, AB și AC, fișierul mai precizează că **orarul pentru Educație fizică se găsește la sala de sport**. Orele din grilă sunt păstrate, dar aplicația afișează și această avertizare.


## Hartă și locație

Toate cele trei pagini ale hărții oficiale UPB — **Noul Local**, **Leu** și **Polizu** — sunt georeferențiate folosind puncte GPS de control. Butonul de localizare de pe hartă:

- cere permisiunea Android de locație la prima folosire;
- afișează poziția printr-un punct albastru și cercul de precizie GPS;
- folosește senzorul de rotație al telefonului pentru săgeata de direcție;
- centrează harta pe poziția curentă când este apăsat;
- nu afișează markerul dacă poziția GPS se află în afara zonei acoperite de pagina selectată.

Interfața folosește un stil mai plat și mai pătrățos, cu colțuri mici și carduri fără umbre puternice, fără să schimbe logica orarului.

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
