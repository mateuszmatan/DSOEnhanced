# DevSecOps Management Portal & DSOEnhanced – Przewodnik Użytkownika

> **Kompleksowa instrukcja dla wersji zintegrowanej z portalem (`DevSecOpsManagementPortal` + biblioteka `DSOEnhanced` w gałęzi `main`).**
> Dokument przeznaczony dla osób nietechnicznych, Product Ownerów, analityków oraz inżynierów wdrażających usługi w standardzie BBH.

---

## Spis Treści

- [1. Wprowadzenie i Koncepcja Rozwiązania](#1-wprowadzenie-i-koncepcja-rozwiązania)
- [2. Tworzenie i Zarządzanie Produktem](#2-tworzenie-i-zarządzanie-produktem)
- [3. Dodawanie i Konfiguracja Serwisu w Portalu](#3-dodawanie-i-konfiguracja-serwisu-w-portalu)
- [4. Projekty Wielomodułowe i Wiele Repozytoriów Bitbucket](#4-projekty-wielomodułowe-i-wiele-repozytoriów-bitbucket)
- [5. Obsługiwane Technologie i Narzędzia Budowania](#5-obsługiwane-technologie-i-narzędzia-budowania)
- [6. Testy Jednostkowe i Raporty Pokrycia Kodu (Code Coverage)](#6-testy-jednostkowe-i-raporty-pokrycia-kodu-code-coverage)
- [7. Bezpieczeństwo Kodu SAST – HCL AppScan Source](#7-bezpieczeństwo-kodu-sast--hcl-appscan-source)
- [8. Bezpieczeństwo Dynamiczne DAST – HCL AppScan Enterprise](#8-bezpieczeństwo-dynamiczne-dast--hcl-appscan-enterprise)
- [9. Jakość Kodu – SonarQube i Sonar Badges](#9-jakość-kodu--sonarqube-i-sonar-badges)
- [10. Bezpieczeństwo Zależności i Automatyczne Poprawki – Nexus IQ i GoldenFix](#10-bezpieczeństwo-zależności-i-automatyczne-poprawki--nexus-iq-i-goldenfix)
- [11. Wdrożenia i Delivery – OpenShift oraz Maszyny Wirtualne (VM)](#11-wdrożenia-i-delivery--openshift-oraz-maszyny-wirtualne-vm)
- [12. Telemetria, Metryki DORA, InfluxDB i Grafana](#12-telemetria-metryki-dora-influxdb-i-grafana)
- [13. Instrukcja Krok po Kroku: Co Gdzie Wpisać w Formularzu](#13-instrukcja-krok-po-kroku-co-gdzie-wpisać-w-formularzu)

---

## 1. Wprowadzenie i Koncepcja Rozwiązania

DevSecOps Management Portal to centralna platforma graficzna banku BBH służąca do definiowania i zarządzania zautomatyzowanymi potokami dostarczania oprogramowania (CI/CD).

### Kluczowe zasady działania:
- **Brak haseł w repozytorium kodu:** Żadne klucze API, hasła ani tokeny nie są wpisywane do kodu aplikacji. Wszystkie poświadczenia są powiązane z bezpiecznym magazynem Jenkins Credentials.
- **Klucz potoku (`pipelineKey`):** Każdy potok otrzymuje unikalny identyfikator. W pliku `Jenkinsfile` w repozytorium znajduje się wyłącznie ten jeden klucz:
  ```groovy
  @Library('DSOEnhanced@main') _
  devSecOpsPipeline(pipelineKey: '5e0f0001-8c2b-4f6a-9d3e-1a7b2c4d0001')
  ```
- **Automatyczna komunikacja:** Podczas uruchomienia zadania w Jenkinsie biblioteka pobiera kompletną konfigurację w formacie JSON z portalu, wykonuje etapy i zapisuje wyniki w bazie telemetrycznej InfluxDB.
- **Profesjonalny interfejs BBH:** Aplikacja posiada formalny interfejs graficzny spełniający wyśrubowane standardy bankowości inwestycyjnej.

---

## 2. Tworzenie i Zarządzanie Produktem

Produkt reprezentuje nadrzędną aplikację biznesową (np. *Payments Hub*, *Custody Reporting*).

### Pola formularza tworzenia produktu:
1. **Business Service:** Wybór departamentu biznesowego z listy rozwijanej (np. *Custody*, *Fund Services*).
2. **Product Code:** Krótki kod identyfikacyjny (2 do 50 wielkich liter, cyfr, znaków `-` lub `_`, zaczynający się od litery, np. `PAYHUB`).
3. **Product Name:** Pełna, czytelna nazwa produktu.
4. **Owner Team:** Nazwa zespołu odpowiedzialnego za produkt.
5. **Contact E-mail:** Adres e-mail zespołu (zalecana skrzynka grupowa).
6. **Jenkins Agents:** Etykiety agentów Jenkinsa, na których ma być uruchamiany build (oddzielone przecinkami, pierwszy staje się domyślnym).

> [!NOTE]
> **Zmiana w architekturze poświadczeń:** Pole *AppScan API Key ID* zostało całkowicie usunięte z poziomu Produktu. Poświadczenia bezpieczeństwa są obecnie konfigurowane bezpośrednio w każdym Serwisie, co gwarantuje spójność i niezależność uprawnień.

---

## 3. Dodawanie i Konfiguracja Serwisu w Portalu

Serwis to niezależny komponent produktu (np. backend API, mikroserwis, panel webowy frontend, moduł bazy danych).

### Nowe funkcje przyspieszające konfigurację:
- **Import config from config.yaml / devsecops_config.yaml:**
  - Możesz wgrać istniejący plik YAML z konfiguracją.
  - Portal odczyta wszystkie parametry i automatycznie wypełni nimi formularz serwisu.
- **Copy values from existing service:**
  - Jeśli Twój produkt posiada już skonfigurowany serwis, możesz skopiować jego ustawienia jednym kliknięciem.
  - Wystarczy wybrać serwis źródłowy i zmienić jedynie specyficzne pola (np. nazwę i repozytorium).
- **Inteligentny przycisk „Add service”:**
  - Przycisk jest nieaktywny (**disabled**), dopóki jakiekolwiek wymagane pole formularza nie jest poprawnie wypełnione.
  - UI wyświetla licznik brakujących pól oraz bezpośrednie linki do sekcji wymagających uzupełnienia.
- **Wartości domyślne (Library Defaults):**
  - Pola formularza, które posiadają zdefiniowane wartości domyślne w ustawieniach globalnych, są automatycznie wypełnione.
  - Każde pole wyświetla szary napis pomocniczy (np. *Library default: mvn clean package*).
- **Dymki pomocy (Tooltips):**
  - Przy każdym polu formularza znajduje się ikona `?` lub interaktywny dymek wyjaśniający prostym językiem przeznaczenie parametru i oczekiwany format.

---

## 4. Projekty Wielomodułowe i Wiele Repozytoriów Bitbucket

Platforma w pełni obsługuje zaawansowane architektury korporacyjne:

### 1. Wiele Serwisów w Jednym Produkcie (Multi-Service)
- W ramach jednego produktu możesz dodać dowolną liczbę serwisów.
- Każdy serwis może znajdować się w **zupełnie innym repozytorium Bitbucket**.
- Każdy serwis definiuje własny adres repozytorium (`scm.bitbucket.repositoryUrl`), branch, poświadczenia oraz listę recenzentów PR.

### 2. Projekty Wielomodułowe (Multi-Module Single Service)
- Jeśli serwis składa się z wielu modułów w jednym repozytorium (np. Maven submodules, Gradle subprojects lub skrypty Liquibase):
  - Etap budowania automatycznie kompiluje wszystkie moduły.
  - Wspólny katalog wersji (*Version Catalogs* np. `libs.versions.toml` lub `platform/build.gradle`) jest centralnie monitorowany i aktualizowany przez proces GoldenFix w ramach pojedynczego Pull Requesta.

---

## 5. Obsługiwane Technologie i Narzędzia Budowania

Portal i biblioteka natywnie wspierają wszystkie technologie używane w BBH:

| Technologia / Język | Narzędzie budowania | Standardowe polecenie budowania | Przeznaczenie |
|---|---|---|---|
| **Java** | Maven (`mvn`) | `clean package -DskipTests` | Backend API, serwisy biznesowe |
| **Kotlin** | Gradle (`gradle`) | `clean assemble -x test` | Nowoczesne mikroserwisy |
| **Groovy** | Gradle / Maven | `clean build -x test` | Narzędzia automatyzacji i biblioteki |
| **Node.js / TypeScript** | npm | `npm run build` | Frontendy webowe, serwisy BFF |
| **Angular** | npm | `npm ci && npm run build --prod` | Aplikacje internetowe użytkownika |
| **Python** | pip / python3 | `pip install -r requirements.txt` | Analityka danych, skrypty |
| **SQL / PL-SQL** | Liquibase | `liquibase update` | Migracje bazy danych Oracle / PostgreSQL |
| **Flutter / Dart** | flutter / pub | `flutter build` | Aplikacje mobilne i desktopowe |

### Etap Stage 0 (Pre-build Frontend):
- Dla aplikacji full-stack (gdzie backend serwuje skompilowany frontend), portal pozwala włączyć budowanie frontendu Node.js przed głównym etapem Maven/Gradle.

---

## 6. Testy Jednostkowe i Raporty Pokrycia Kodu (Code Coverage)

Biblioteka automatycznie dba o jakość kodu i egzekwowanie testów jednostkowych:

- **Automatyczne wyszukiwanie raportów:**
  - System automatycznie przeszukuje wszystkie podkatalogi (`build/`, `target/`, `coverage/`, `release/`).
  - Obsługuje raporty **JaCoCo (XML)** dla Javy, Kotlina i Groovy.
  - Obsługuje raporty **LCOV (`lcov.info`)** dla Node.js, Angulara, Pythona i Fluttera.
- **Raportowanie per moduł:**
  - W podsumowaniu przebiegu każdy raport i moduł posiada osobną linię ze swoją ścieżką, liczbą linii kodu i procentem pokrycia.
- **Średnia dla całego projektu:**
  - System wylicza jedną zbiorczą średnią pokrycia kodu dla całego serwisu/projektu (`All services`).
- **Release Gate:**
  - Jeżeli średnie pokrycie kodu serwisu jest niższe niż wymagany próg (domyślnie 80%), pipeline zostaje zatrzymany, a wdrożenie zablokowane.

---

## 7. Bezpieczeństwo Kodu SAST – HCL AppScan Source

Statyczna analiza kodu wykrywa podatności w kodzie źródłowym przed jego uruchomieniem.

### Nowości i usprawnienia w konfiguracji:
- **Asynchroniczne skanowanie w tle (Skan równoległy):**
  - Generowanie pliku IRX następuje natychmiast po zbudowaniu kodu.
  - Skanowanie w chmurze HCL ASOC jest kolejkowane w tle i wykonuje się **równolegle** z analizami Nexus IQ oraz SonarQube.
  - Czas wykonania pipeline'u skrócił się nawet o 60%.
- **Uproszczona konfiguracja w UI:**
  - `asoc.token` został przeniesiony z ustawień zaawansowanych do **głównej sekcji SAST**.
  - W ustawieniach zaawansowanych dodano pole `sast.scanName` (pozwala nadać własną nazwę skanowania).
  - Poświadczenia `asoc.keyId` i `asoc.token` są konfigurowane na poziomie każdego serwisu.

---

## 8. Bezpieczeństwo Dynamiczne DAST – HCL AppScan Enterprise

Dynamiczna analiza bezpieczeństwa bada działającą aplikację webową pod kątem podatności sieciowych.

### Wymagane parametry:
- **Target URL (`dast.targetUrl`):** Adres URL wdrożonej aplikacji w środowisku testowym (np. `https://dev-payhub.bbh.com`).
- **Presence ID (`dast.presenceId`):** Identyfikator obecności agenta AppScan wewnątrz sieci BBH, umożliwiający bezpieczne skanowanie aplikacji za firmowym firewallem.
- **Credentials:** Identyfikator poświadczeń w Jenkinsie używanych do logowania w skanowanej aplikacji.

---

## 9. Jakość Kodu – SonarQube i Sonar Badges

SonarQube analizuje kod pod kątem długu technologicznego, złożoności cyklomatycznej oraz zapachów kodu (code smells).

### Kluczowe ułatwienia w portalu:
- **Główna sekcja SonarQube:**
  - Pola `Installation Name`, `Auth Token` oraz `Badge Token` znajdują się bezpośrednio w sekcji głównej.
- **Dwa alternatywne sposoby konfiguracji Badge Tokena:**
  1. Podanie tokena bezpośrednio w polu **Badge Token**.
  2. LUB wskazanie bezpiecznego sekretu z Jenkinsa w polu **Badge Credentials ID**.
  > [!TIP]
  > Wystarczy skonfigurować **jedno** z powyższych rozwiązań. Użytkownik nie musi podawać obu wartości!
- **Pełne wsparcie technologiczne:**
  - SonarQube analizuje Javę, Kotlin, TypeScript, skrypty SQL/PL-SQL (Liquibase) oraz aplikacje Flutter/Dart.

---

## 10. Bezpieczeństwo Zależności i Automatyczne Poprawki – Nexus IQ i GoldenFix

System chroni aplikacje przed znanymi podatnościami (CVE) w bibliotekach open-source.

### Ograniczenie w UI:
- **Dokładnie jedna aplikacja Nexus IQ:**
  - W formularzu serwisu można skonfigurować tylko **jedną aplikację Nexus IQ** (`applicationId`).
  - Interfejs uniemożliwia dodanie wielu aplikacji, co eliminuje błędy konfiguracyjne.

### Funkcja GoldenFix (Automatyczne naprawianie podatności):
- Jeżeli Nexus IQ wykryje podatną bibliotekę, GoldenFix sprawdza, czy istnieje bezpieczna wersja.
- **Automatyczny Pull Request:** GoldenFix sam otwiera Pull Request w Bitbuckecie, podnosząc wersję zależności w pliku `pom.xml`, `build.gradle`, `package.json`, `requirements.txt` lub katalogu wersji (`libs.versions.toml`).
- Po zmergowaniu PR aplikacja jest automatycznie zabezpieczona.

---

## 11. Wdrożenia i Delivery – OpenShift oraz Maszyny Wirtualne (VM)

Moduł wdrożeniowy odpowiada za publikację artefaktów oraz instalację aplikacji na środowiskach docelowych.

### Nowa funkcja: Checkbox „Disable Nexus Delivery”
- Umożliwia wyłączenie publikacji artefaktów do repozytorium Nexus:
  - Dla VM ustawia: `deploy.vm.rd.nexusDeliveryEnabled: false`
  - Dla OpenShift ustawia: `deploy.openshift.rd.nexusDeliveryEnabled: false`
- **Ułatwienie dla użytkownika:** Zaznaczenie checkboxa ukrywa i wyłącza walidację zbędnych pól delivery, czyszcząc formularz.

### Naprawiona walidacja OpenShift QC Region:
- Sekcja regionu QC jest w 100% **opcjonalna**.
- Jeżeli nie wdrażasz aplikacji do regionu QC, formularz jest w pełni poprawny (**valid**) i można go zapisać.
- Walidacja pól regionu QC uruchamia się wyłącznie wtedy, gdy zaczniesz go aktywnie konfigurować.

### Obsługiwane typy środowisk:
1. **OpenShift (Kontenery):** Wdrożenie do klastrów Kubernetes/OpenShift (przestrzeń nazw, deployment, service).
2. **Maszyny Wirtualne (VM):** Integracja z IBM DevOps / IBM UrbanCode Deploy.

---

## 12. Telemetria, Metryki DORA, InfluxDB i Grafana

Każde uruchomienie potoku wysyła szczegółowe dane telemetryczne do centralnej bazy InfluxDB:

- **Metryki DORA (DevOps Research and Assessment):**
  1. **Deployment Frequency:** Jak często zmiany trafiają na produkcję.
  2. **Lead Time for Changes:** Czas od zatwierdzenia kodu do uruchomienia na środowisku.
  3. **Change Failure Rate:** Odsetek wdrożeń zakończonych awarią.
  4. **Time to Restore Service (MTTR):** Czas potrzebny na przywrócenie sprawności po błędzie.
- **Metryki etapów:** Czas trwania kompilacji, liczba testów jednostkowych, pokrycie kodu per moduł, wykryte podatności SAST/DAST/Nexus IQ.
- **Dashboardy:**
  - W portalu: zakładka **Pipeline Monitoring** (przegląd ostatnich przebiegów i wskaźników DORA).
  - W Grafanie: rozbudowane wykresy telemetryczne dla całego departamentu.

---

## 13. Instrukcja Krok po Kroku: Co Gdzie Wpisać w Formularzu

Poniższa tabela stanowi kompletny przewodnik po polach formularza edycji serwisu w Portalu:

| Nazwa pola w Portalu | Co oznacza (w prostych słowach) | Przykładowa wartość | Skąd wziąć wartość | Czy wymagane? |
|---|---|---|---|:---:|
| **Service Name** | Unikalna nazwa serwisu w projekcie | `backend-api` lub `web-gui` | Dowolna nazwa komponentu | <span class="badge badge-required">TAK</span> |
| **Bitbucket Repo URL** | Adres repozytorium kodu | `ssh://git@bitbucket.bbh.com/pay/api.git` | Z Bitbucketa (Clone -> SSH) | <span class="badge badge-required">TAK</span> |
| **Git Branch** | Domyślna gałąź do budowania | `main` lub `master` | Standard Twojego repozytorium | <span class="badge badge-required">TAK</span> |
| **Build Tool** | Narzędzie do kompilacji kodu | `maven`, `gradle`, `npm` lub `pip` | Zależy od języka programowania | <span class="badge badge-required">TAK</span> |
| **Build Tasks / Command** | Polecenie uruchamiające kompilację | `clean package -DskipTests` | Domyślne dla Twojego narzędzia | <span class="badge badge-required">TAK</span> |
| **Unit Test Tasks** | Polecenie uruchamiające testy | `test` lub `test jacocoTestReport` | Zależy od konfiguracji testów | <span class="badge badge-optional">OPCJA</span> |
| **Coverage Minimum** | Minimalny wymagany procent pokrycia | `80` | Standard BBH (domyślnie 80%) | <span class="badge badge-default">DOMYŚLNE</span> |
| **AppScan ASOC Key ID** | Identyfikator klucza API do SAST | `asoc-key-payments-api` | Identyfikator Secret Text w Jenkinsie | <span class="badge badge-required">TAK</span> |
| **AppScan ASOC Token** | Token powiązany z kluczem SAST | `asoc-secret-payments-api` | Identyfikator Secret Text w Jenkinsie | <span class="badge badge-required">TAK</span> |
| **SAST Scan Name** | Własna nazwa skanowania w ASOC | `PAYHUB-API-Nightly` | Dowolna czytelna etykieta | <span class="badge badge-optional">OPCJA</span> |
| **Sonar Installation** | Nazwa instancji SonarQube w Jenkinsie | `BBH-SonarQube` | Standardowa nazwa w firmie | <span class="badge badge-default">DOMYŚLNE</span> |
| **Sonar Auth Token** | Token uwierzytelniający do SonarQube | `sonar-auth-token-id` | Identyfikator Secret Text w Jenkinsie | <span class="badge badge-required">TAK</span> |
| **Sonar Badge Token** | Token do generowania odznak (opcja 1) | `sonar-badge-token-raw-value` | Z profilu projektu w SonarQube | <span class="badge badge-optional">OPCJA (1 z 2)</span> |
| **Sonar Badge Creds ID** | Token odznak z Jenkinsa (opcja 2) | `sonar-badge-credentials-id` | Identyfikator Secret Text w Jenkinsie | <span class="badge badge-optional">OPCJA (1 z 2)</span> |
| **Nexus IQ Application ID** | Unikalne ID aplikacji w Nexus IQ | `payhub-backend-api` | Dokładna nazwa aplikacji z Nexusa | <span class="badge badge-required">TAK</span> |
| **Disable Nexus Delivery** | Wyłączenie publikowania w Nexus | Zaznaczony `[X]` lub odznaczony `[ ]` | Zaznacz, jeśli nie publikujesz | <span class="badge badge-optional">OPCJA</span> |
| **OpenShift Namespace** | Projekt/Namespace w klastrze kontenerów | `payhub-dev` | Z platformy OpenShift | <span class="badge badge-optional">Dla OpenShift</span> |
| **VM Application Name** | Nazwa aplikacji w IBM UrbanCode Deploy | `PAYHUB_CORE` | Z konsoli UrbanCode Deploy | <span class="badge badge-optional">Dla VM</span> |

---

### Procedura Krok po Kroku:
1. **Wejdź do Portalu:** Otwórz Portal DevSecOps i przejdź do zakładki **Admin -> Products**.
2. **Wybierz lub utwórz Produkt:** Kliknij *Add Product*, podaj kod produktu (np. `PAYHUB`) oraz wybierz agentów Jenkinsa.
3. **Dodaj Serwis:**
   - Skorzystaj z opcji **Import config** (wgraj plik `devsecops_config.yaml`), LUB
   - Użyj **Copy values** (skopiuj z istniejącego serwisu), LUB
   - Wypełnij formularz ręcznie, korzystając z podpowiedzi i dymków pomocy (tooltips).
4. **Skonfiguruj Bezpieczeństwo:**
   - W sekcji SAST wskaż poświadczenia AppScan ASOC.
   - W sekcji SonarQube podaj token lub poświadczenie badge.
   - W sekcji Nexus IQ wpisz jedno ID aplikacji.
5. **Zapisz Serwis:** Przycisk *Add service* podświetli się na niebiesko, gdy wszystkie wymagane pola będą poprawne.
6. **Pobierz Jenkinsfile:** Przejdź do widoku potoków i skopiuj wygenerowany jednolinijkowy `Jenkinsfile` z kluczem `pipelineKey` do swojego repozytorium. Potok jest gotowy do uruchomienia!
