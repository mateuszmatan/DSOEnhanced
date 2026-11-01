# DevSecOps Jenkins Shared Library – Przewodnik Standalone (Bez Portalu)

> **Kompleksowa instrukcja dla potoków CI/CD bez integracji z portalem (`DSOEnhanced` w gałęzi `DSOEnhancedWithoutPortal`).**
> Cała konfiguracja opiera się wyłącznie na dwóch plikach w Twoim repozytorium kodu: `Jenkinsfile` oraz `devsecops_config.yaml`.
> Dokument przeznaczony dla osób nietechnicznych, Product Ownerów, analityków i inżynierów.

---

## Spis Treści

- [1. Wprowadzenie i Model Działania Bez Portalu](#1-wprowadzenie-i-model-działania-bez-portalu)
- [2. Plik Jenkinsfile – Definicja Potoku w Repozytorium](#2-plik-jenkinsfile--definicja-potoku-w-repozytorium)
- [3. Plik devsecops_config.yaml – Główny Plik Konfiguracji](#3-plik-devsecops_configyaml--główny-plik-konfiguracji)
- [4. Obsługa Wielu Serwisów i Własnych Repozytoriów Bitbucket](#4-obsługa-wielu-serwisów-i-własnych-repozytoriów-bitbucket)
- [5. Obsługiwane Technologie i Narzędzia Budowania](#5-obsługiwane-technologie-i-narzędzia-budowania)
- [6. Testy Jednostkowe i Raporty Pokrycia Kodu (JaCoCo i LCOV)](#6-testy-jednostkowe-i-raporty-pokrycia-kodu-jacoco-i-lcov)
- [7. Bezpieczeństwo Kodu SAST – HCL AppScan Source (Skany w Tle)](#7-bezpieczeństwo-kodu-sast--hcl-appscan-source-skany-w-tle)
- [8. Bezpieczeństwo Dynamiczne DAST – HCL AppScan Enterprise](#8-bezpieczeństwo-dynamiczne-dast--hcl-appscan-enterprise)
- [9. Jakość Kodu – SonarQube i Alternatywne Badge Tokeny](#9-jakość-kodu--sonarqube-i-alternatywne-badge-tokeny)
- [10. Bezpieczeństwo Zależności i Automatyczne Poprawki – Nexus IQ i GoldenFix](#10-bezpieczeństwo-zależności-i-automatyczne-poprawki--nexus-iq-i-goldenfix)
- [11. Wdrożenia i Opcja „Disable Nexus Delivery” (OpenShift i VM)](#11-wdrożenia-i-opcja-disable-nexus-delivery-openshift-i-vm)
- [12. Telemetria, Metryki DORA, InfluxDB i Grafana](#12-telemetria-metryki-dora-influxdb-i-grafana)
- [13. Kompletny Szablon devsecops_config.yaml z Opisem Pól (Co Gdzie Wpisać)](#13-kompletny-szablon-devsecops_configyaml-z-opisem-pól-co-gdzie-wpisać)

---

## 1. Wprowadzenie i Model Działania Bez Portalu

W gałęzi `DSOEnhancedWithoutPortal` biblioteka DevSecOps działa w sposób w pełni autonomiczny:
- **Brak zależności od portalu:** Pipeline nie odpytuje zewnętrznego serwera portalu o konfigurację.
- **Konfiguracja jako kod (Configuration as Code):** Wszystkie parametry potoku znajdują się w pliku `devsecops_config.yaml` (lub kompatybilnym `config.yaml`) bezpośrednio w głównym katalogu Twojego repozytorium kodu.
- **Wbudowane wartości domyślne (Library Defaults):** Globalne standardy BBH (np. progi pokrycia kodu, domyślne timeouty, parametry InfluxDB) są zaszyte w bibliotece (`defaults.yaml`), więc w swoim pliku definiujesz tylko parametry specyficzne dla Twojej aplikacji.

---

## 2. Plik Jenkinsfile – Definicja Potoku w Repozytorium

Plik `Jenkinsfile` umieszczony w katalogu głównym repozytorium jest prosty i zwięzły. Zamiast klucza portalu wskazuje nazwy serwisów oraz agentów Jenkinsa.

### Przykładowy plik `Jenkinsfile`:
```groovy
@Library('DSOEnhanced@DSOEnhancedWithoutPortal') _

devSecOpsPipeline(
    projectNames: 'backend-api,web-gui',
    agentNames: ['linux-rhel8', 'linux-docker']
)
```

### Dostępne typy potoków:
- `devSecOpsPipeline`: Pełny pipeline dostarczania (budowa, testy, SAST, DAST, SonarQube, Nexus IQ, delivery, deployment).
- `devSecOpsSecurityPipeline`: Szybki skan bezpieczeństwa (budowa, SAST, SonarQube, Nexus IQ).
- `devSecOpsNexusIqGoldenFixPipeline`: Potok do automatycznego podnoszenia podatnych zależności i otwierania PR-ów.

---

## 3. Plik devsecops_config.yaml – Główny Plik Konfiguracji

Plik `devsecops_config.yaml` musi znajdować się w katalogu głównym repozytorium (root).

### Zasada działania:
- Biblioteka czyta w pierwszej kolejności plik `devsecops_config.yaml`.
- Jeżeli taki plik nie istnieje, czytany jest tradycyjny plik `config.yaml`.
- Z pliku pobierana jest sekcja `projects:`, w której zdefiniowany jest każdy serwis Twojego projektu.

---

## 4. Obsługa Wielu Serwisów i Własnych Repozytoriów Bitbucket

Biblioteka umożliwia elastyczne zarządzanie architekturami wielomodułowymi i wieloserwisowymi:

### 1. Wiele Serwisów w Jednym Projekcie (Multi-Service)
W sekcji `projects:` możesz zdefiniować dowolną liczbę komponentów:
- Każdy serwis może posiadać **własne, niezależne repozytorium Bitbucket** (`scm.bitbucket.repositoryUrl`).
- Każdy serwis posiada własną docelową gałąź Git (`scm.bitbucket.targetBranch`) oraz własną listę recenzentów pull requestów (`scm.bitbucket.reviewers`).

### 2. Projekty Wielomodułowe (Multi-Module Single Service)
- Dla projektów z wieloma podmodułami Maven (`pom.xml`) lub Gradle (`subprojects`) biblioteka automatycznie kompiluje wszystkie moduły.
- W przypadku korzystania ze wspólnego katalogu wersji (*Version Catalogs* np. `gradle/libs.versions.toml` lub `platform/build.gradle`), proces GoldenFix otwiera **jeden zbiorczy Pull Request** dla całego repozytorium.

---

## 5. Obsługiwane Technologie i Narzędzia Budowania

Biblioteka wspiera wszystkie środowiska uruchomieniowe i narzędzia kompilacji:

- **Java / Kotlin / Groovy:**
  - Budowanie przez Maven (`tool: 'maven'`) lub Gradle (`tool: 'gradle'`).
  - Przykładowe polecenie: `clean package -DskipTests` lub `clean assemble -x test`.
- **Node.js / Angular:**
  - Budowanie przez npm (`tool: 'npm'`).
  - Polecenie: `npm ci && npm run build --prod`.
  - Możliwość uruchomienia jako **Stage 0** (kompilacja frontendu przed uruchomieniem Mavena/Gradle dla backendu).
- **Python:**
  - Budowanie przez pip (`tool: 'pip'`).
  - Instalacja zależności: `pip install -r requirements.txt`.
- **SQL / PL-SQL (Liquibase):**
  - Automatyczne wykrywanie changelogów bazy danych (`detectTool()`).
  - Polecenie: `liquibase update`.
  - Weryfikacja składni i analiza statyczna skryptów w SonarQube.
- **Flutter / Dart:**
  - Budowanie aplikacji mobilnych i desktopowych.
  - Generowanie raportów LCOV i analiza w SonarQube.

---

## 6. Testy Jednostkowe i Raporty Pokrycia Kodu (JaCoCo i LCOV)

- **Wieloraportowe pokrycie kodu (Multi-Report Coverage):**
  - Biblioteka automatycznie skanuje repozytorium i odnajduje wszystkie pliki raportów:
    - **JaCoCo XML:** raporty z testów Java/Kotlin/Groovy (`jacoco.xml`, `jacocoTestReport.xml`).
    - **LCOV (`lcov.info`):** raporty z testów JavaScript, TypeScript/Angular, Python i Flutter.
- **Szczegółowa prezentacja wyników:**
  - Każdy raport i każdy moduł ma dedykowaną linię podsumowania z procentem pokrycia i liczbą linii kodu.
  - Generowana jest **jedna zbiorcza linia podsumowania projektu** (`All services`), prezentująca średnie pokrycie.
- **Bramka Jakości (Release Gate):**
  - Opcja `coverage.minimum: 80` wymusza osiągnięcie wymaganego progu. Jeśli średnia jest niższa, build zostaje przerwany, zabezpieczając środowisko przed błędami.

---

## 7. Bezpieczeństwo Kodu SAST – HCL AppScan Source (Skany w Tle)

Statyczna analiza bezpieczeństwa kodu:

- **Asynchroniczne skany w tle:**
  - Po wygenerowaniu pliku IRX skanowanie w HCL ASOC jest uruchamiane asynchronicznie w tle.
  - W tym samym czasie potok wykonuje analizy Nexus IQ oraz SonarQube. Wyniki SAST są odbierane na końcu etapu statycznego.
- **Konfiguracja poświadczeń:**
  - Każdy serwis konfiguruje własne poświadczenia w sekcji `asoc:`:
    ```yaml
    asoc:
      keyId: 'asoc-api-key-id'
      token: 'asoc-secret-token-id'
      appId: '12345'
    ```
- **Własna nazwa skanowania:**
  - Opcjonalne pole `sast.scanName` pozwala nadać niestandardową nazwę w konsoli ASOC.

---

## 8. Bezpieczeństwo Dynamiczne DAST – HCL AppScan Enterprise

Dynamiczne skanowanie działającej aplikacji webowej:

```yaml
dast:
  targetUrl: 'https://dev-payhub.bbh.com'
  presenceId: 'presence-internal-bbh-id'
  credentialsId: 'dast-test-user-creds'
```
- `presenceId` umożliwia bezpieczne skanowanie aplikacji umieszczonych w wewnętrznej sieci banku.

---

## 9. Jakość Kodu – SonarQube i Alternatywne Badge Tokeny

Konfiguracja analizy jakościowej kodu w SonarQube:

```yaml
tools:
  sonar:
    installationName: 'BBH-SonarQube'
    authToken: 'sonar-auth-credentials-id'
    # Sposób 1: bezpośredni badge token
    badgeToken: 'raw-token-value-from-sonar'
    # Sposób 2 (alternatywny): sekret w Jenkinsie
    badgeCredentialsId: 'jenkins-sonar-badge-secret-id'
```
> [!TIP]
> Wystarczy podać **jedno** z dwóch rozwiązań: `badgeToken` LUB `badgeCredentialsId`.

---

## 10. Bezpieczeństwo Zależności i Automatyczne Poprawki – Nexus IQ i GoldenFix

- **Nexus IQ:**
  - Analiza bibliotek pod kątem podatności open-source:
    ```yaml
    tools:
      nexusIq:
        applicationId: 'payhub-backend-api'
    ```
- **GoldenFix i Golden Pull Request:**
  - Włączenie automatycznego leczenia podatności:
    ```yaml
    goldenFix:
      enabled: true
      pullRequest: true
    ```
  - Po wykryciu podatności biblioteka sama tworzy branch i otwiera Pull Request w Bitbuckecie podnoszący wersję biblioteki do bezpiecznej.

---

## 11. Wdrożenia i Opcja „Disable Nexus Delivery” (OpenShift i VM)

### Nowa funkcja: Wyłączenie publikowania w Nexusie
Jeżeli Twoja aplikacja nie wymaga publikacji artefaktu do repozytorium Nexus, wystarczy ustawić:
- Dla wdrożeń na maszyny wirtualne: `deploy.vm.rd.nexusDeliveryEnabled: false`
- Dla wdrożeń na OpenShift: `deploy.openshift.rd.nexusDeliveryEnabled: false`
- Biblioteka automatycznie pominie etap wysyłki i nie będzie walidować parametrów delivery.

### Opcjonalny region QC w OpenShift:
- Sekcja `deploy.openshift.rd.regions.qc` jest w pełni opcjonalna. Jeżeli jej nie zdefiniujesz, pipeline wykona wdrożenie standardowe bez błędów.

---

## 12. Telemetria, Metryki DORA, InfluxDB i Grafana

Każdy etap pipeline'u automatycznie emituje metryki telemetryczne do centralnej bazy InfluxDB:
- **Metryki DORA:** Częstotliwość wdrożeń (Deployment Frequency), Czas przejścia zmiany (Lead Time), Wskaźnik błędnych wdrożeń (Change Failure Rate), Średni czas naprawy (MTTR).
- **Metryki operacyjne:** Pokrycie testami, czas trwania poszczególnych etapów, statystyki podatności.
- Dane są natychmiast dostępne na firmowych dashboardach w Grafanie.

---

## 13. Kompletny Szablon devsecops_config.yaml z Opisem Pól (Co Gdzie Wpisać)

Poniżej znajduje się kompletny, gotowy do skopiowania szablon pliku `devsecops_config.yaml`:

```yaml
# ==============================================================================
# BBH DevSecOps Pipeline Configuration (Standalone / Without Portal)
# Plik: devsecops_config.yaml (umieść w katalogu głównym repozytorium)
# ==============================================================================

projects:
  # ----------------------------------------------------------------------------
  # SERWIS 1: Backend API (np. Java / Spring Boot z Mavenem)
  # ----------------------------------------------------------------------------
  backend-api:
    # 1. Konfiguracja Repozytorium Kodu (Bitbucket)
    scm:
      bitbucket:
        repositoryUrl: 'ssh://git@bitbucket.bbh.com/pay/payhub-backend.git'
        credentialsId: 'bitbucket-ssh-key'
        targetBranch: 'main'
        reviewers: ['jan.kowalski@bbh.com', 'anna.nowak@bbh.com']

    # 2. Kompilacja i Budowanie Kodu
    build:
      tool: 'maven'                               # Dostępne: maven, gradle, npm, pip
      tasks: 'clean package -DskipTests'          # Polecenie kompilacji

    # 3. Testy Jednostkowe i Pokrycie Kodu
    unitTests:
      tasks: 'test'                               # Zadanie uruchamiające testy
    coverage:
      minimum: 80                                 # Wymagane pokrycie kodu (%)
      # reportPath: puste = automatyczne wyszukiwanie JaCoCo i LCOV w całym projekcie

    # 4. Bezpieczeństwo Statyczne Kodu (HCL AppScan SAST)
    asoc:
      keyId: 'asoc-key-id-backend'                # ID poświadczenia w Jenkinsie
      token: 'asoc-secret-id-backend'             # ID poświadczenia w Jenkinsie
      appId: '10982'                              # ID aplikacji w chmurze HCL ASOC
    sast:
      scanName: 'PAYHUB-BACKEND-API'              # Opcjonalna nazwa skanowania

    # 5. Bezpieczeństwo Zależności i Automatyczne Poprawki (Nexus IQ)
    tools:
      nexusIq:
        applicationId: 'payhub-backend-api'       # Dokładnie JEDNA aplikacja na serwis
      sonar:
        installationName: 'BBH-SonarQube'         # Nazwa serwera w Jenkinsie
        authToken: 'sonar-auth-token-id'          # ID poświadczenia w Jenkinsie
        # Podaj badgeToken LUB badgeCredentialsId:
        badgeCredentialsId: 'sonar-badge-secret-id'

    # 6. Automatyczne Poprawki Zależności (GoldenFix)
    goldenFix:
      enabled: true                               # Włączenie skanowania GoldenFix
      pullRequest: true                           # Automatyczne otwieranie Pull Requesta

    # 7. Wdrożenie i Delivery
    deploy:
      openshift:
        rd:
          nexusDeliveryEnabled: false             # true = publikacja w Nexus; false = pominięcie
          appName: 'payhub-backend'
          namespace: 'payhub-dev'
          deployment: 'payhub-backend-deployment'

  # ----------------------------------------------------------------------------
  # SERWIS 2: Frontend Web (np. Angular / Node.js)
  # ----------------------------------------------------------------------------
  web-gui:
    scm:
      bitbucket:
        repositoryUrl: 'ssh://git@bitbucket.bbh.com/pay/payhub-gui.git'
        credentialsId: 'bitbucket-ssh-key'
        targetBranch: 'main'
        reviewers: ['jan.kowalski@bbh.com']

    build:
      tool: 'npm'
      tasks: 'npm ci && npm run build --prod'

    unitTests:
      tasks: 'npm test -- --watch=false --browsers=ChromeHeadless'
    coverage:
      minimum: 80

    asoc:
      keyId: 'asoc-key-id-gui'
      token: 'asoc-secret-id-gui'
      appId: '10983'

    tools:
      nexusIq:
        applicationId: 'payhub-web-gui'
      sonar:
        installationName: 'BBH-SonarQube'
        authToken: 'sonar-auth-token-id'
        badgeToken: 'raw-badge-token-gui'

    goldenFix:
      enabled: true
      pullRequest: true

    deploy:
      openshift:
        rd:
          nexusDeliveryEnabled: false
          appName: 'payhub-gui'
          namespace: 'payhub-dev'
          deployment: 'payhub-gui-deployment'
```

---

### Tabela Wyjaśniająca Parametry dla Osoby Nietechnicznej:

| Parametr YAML | Do czego służy | Przykładowa wartość | Czy pole jest wymagane? |
|---|---|---|:---:|
| `projects.<service>` | Nazwa Twojego serwisu | `backend-api` | <span class="badge badge-required">TAK</span> |
| `scm.bitbucket.repositoryUrl` | Link do repozytorium kodu w Bitbucket | `ssh://git@bitbucket.bbh.com/...` | <span class="badge badge-required">TAK</span> |
| `scm.bitbucket.targetBranch` | Główna gałąź Git do budowania | `main` | <span class="badge badge-required">TAK</span> |
| `build.tool` | Narzędzie budujące | `maven`, `gradle`, `npm`, `pip` | <span class="badge badge-required">TAK</span> |
| `build.tasks` | Komenda uruchamiająca budowanie | `clean package -DskipTests` | <span class="badge badge-required">TAK</span> |
| `coverage.minimum` | Minimalny procent pokrycia testami | `80` | <span class="badge badge-default">DOMYŚLNE 80%</span> |
| `asoc.keyId` i `asoc.token` | Poświadczenia AppScan SAST | `asoc-cred-id` (z Jenkinsa) | <span class="badge badge-required">TAK</span> |
| `sast.scanName` | Opcjonalna nazwa skanu w chmurze ASOC | `PAYHUB-NIGHTLY` | <span class="badge badge-optional">OPCJA</span> |
| `tools.sonar.authToken` | Poświadczenie do SonarQube | `sonar-token-id` (z Jenkinsa) | <span class="badge badge-required">TAK</span> |
| `tools.sonar.badgeToken` | Token badge (sposób bezpośredni) | `token-string` | <span class="badge badge-optional">1 Z 2</span> |
| `tools.sonar.badgeCredentialsId` | Token badge (sposób przez Jenkins) | `sonar-badge-cred` | <span class="badge badge-optional">1 Z 2</span> |
| `tools.nexusIq.applicationId` | Pojedyncze ID aplikacji w Nexus IQ | `payhub-backend-api` | <span class="badge badge-required">TAK</span> |
| `goldenFix.pullRequest` | Automatyczne tworzenie Pull Requesta | `true` | <span class="badge badge-default">DOMYŚLNE TRUE</span> |
| `nexusDeliveryEnabled` | Wyłączenie publikowania artefaktów w Nexus | `false` | <span class="badge badge-optional">OPCJA</span> |
