# Blog Stats API

Microservice Spring Boot (Java 21) qui collecte les événements de lecture du blog Symfony
(`view`, `read`) et expose des statistiques en REST : performance d'un article, top des articles,
tendance des vues par jour. Il a sa propre base MariaDB et ne lit jamais celle de Symfony.

- **Ingestion** `/api/events/**` : appelée par le navigateur, publique, CORS limité au blog, 60 événements / min / IP.
- **Stats** `/api/stats/**` et **synchro des articles** `/api/articles/**` : appelées par le backend Symfony avec un
  JWT applicatif (`Authorization: Bearer <jwt>`) obtenu sur `POST /api/auth/token` (OAuth2 client credentials).
- Horodatages en ISO 8601 UTC ; jours et périodes calculés en heure française (`Europe/Paris`).

Spécification complète : [PRD.md](PRD.md).

## Lancer avec Docker

```bash
docker compose up --build
```

Démarre `stats-db` (MariaDB 11.4, publiée sur `127.0.0.1:3307` uniquement, volume `stats-db-data`) puis
`stats-api` (port 8080) dès que la base est prête. Flyway crée le schéma. Aucune autre étape : chaque variable
a une valeur par défaut. Pour les changer : `cp .env.example .env` puis éditer `.env`.
Le port 3307 (et non 3306) laisse la place au MariaDB/MySQL local (XAMPP) du blog Symfony ; le blog lui-même
(`symfony serve`, port 8000) est dans les origines CORS par défaut.
Ces valeurs par défaut (client `symfony-blog` / `change-me`, clé JWT de développement, mots de passe
`stats` / `root`) ne conviennent qu'au poste de développement : voir [Mise en production](#mise-en-production).

Données de démonstration (articles et événements générés au démarrage) :

```bash
SPRING_PROFILES_ACTIVE=demo docker compose up --build
```

Remise à zéro de la base : `docker compose down -v`.

## Lancer sans Docker (H2 en mémoire)

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local,demo
```

Le profil `local` remplace MariaDB par H2 (mode MariaDB, schéma créé par Hibernate, Flyway désactivé) ;
`demo` ajoute les données de démonstration. Client par défaut : `symfony-blog` / `change-me`.

## Tests

```bash
./mvnw test     # tests unitaires, @WebMvcTest, démarrage complet sur H2
./mvnw verify   # + rapport de couverture JaCoCo (target/site/jacoco/index.html), seuil 70 % sur service et controller
```

Les tests Testcontainers (vraie MariaDB 11.4) tournent si Docker est disponible, sinon ils sont ignorés.

## Documentation OpenAPI

- Swagger UI : <http://localhost:8080/swagger-ui.html>. Bouton *Authorize* → schéma `oauth2` (client credentials) :
  saisir `client_id` / `client_secret`, cocher les scopes, Swagger UI demande lui-même le jeton sur `/api/auth/token`.
  Ou schéma `bearerJwt` : coller un jeton déjà obtenu.
- JSON OpenAPI : <http://localhost:8080/v3/api-docs>

## Authentification : JWT applicatif (OAuth2 client credentials)

Le consommateur des routes protégées est le **backend Symfony**, pas un utilisateur : il s'authentifie en tant
qu'application. Il échange son identifiant et son secret (`STATS_CLIENT_ID` / `STATS_CLIENT_SECRET`) contre un
JWT court, puis l'envoie dans `Authorization: Bearer <jwt>` jusqu'à son expiration.

Pourquoi un JWT applicatif plutôt qu'une clé d'API statique : le secret du client ne circule que vers
`/api/auth/token` (limité à 10 demandes / min / IP), jamais sur les autres appels ; un jeton intercepté expire
au bout de 15 min ; chaque jeton porte des **scopes** (`stats:read`, `articles:write`) qui limitent ce qu'il
permet ; la clé de signature ne quitte jamais l'API.

```bash
API=http://localhost:8080

# 1. Jeton (HTTP Basic client_id:client_secret) → 200
curl -s -u symfony-blog:change-me -d grant_type=client_credentials "$API/api/auth/token"
# {"access_token":"eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9…","token_type":"Bearer","expires_in":900,"scope":"stats:read articles:write"}

TOKEN=$(curl -s -u symfony-blog:change-me -d grant_type=client_credentials "$API/api/auth/token" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

# Jeton limité à un sous-ensemble des scopes du client
curl -s -u symfony-blog:change-me -d grant_type=client_credentials -d scope=stats:read "$API/api/auth/token"

# Identifiants dans le formulaire plutôt qu'en HTTP Basic (accepté aussi, jamais les deux à la fois)
curl -s -d grant_type=client_credentials -d client_id=symfony-blog -d client_secret=change-me "$API/api/auth/token"
```

| Élément | Valeur |
| --- | --- |
| Requête | `POST /api/auth/token`, `Content-Type: application/x-www-form-urlencoded`, `grant_type=client_credentials`, `scope` facultatif (sous-ensemble séparé par des espaces ; absent = tous les scopes du client) |
| Authentification du client | HTTP Basic `client_id:client_secret` (RFC 6749 §2.3.1 : chaque partie encodée en URL avant le Base64 ; un secret hexadécimal n'a rien à encoder) ou paramètres `client_id` / `client_secret` |
| Réponse 200 | `{"access_token","token_type":"Bearer","expires_in":900,"scope"}`, `Cache-Control: no-store` |
| Erreurs | RFC 9457 + propriété `error` (RFC 6749) : `401 invalid_client` (client inconnu ou mauvais secret, `WWW-Authenticate: Basic realm="stats-api"`), `400 invalid_request` (`grant_type` absent, paramètre répété, deux méthodes d'authentification), `400 unsupported_grant_type`, `400 invalid_scope`, `429` au-delà de 10 demandes / min / IP |
| JWT | HS256 signé avec `STATS_JWT_SECRET` ; claims `iss`=`stats-api`, `aud`=`stats-api` (audience unique, sérialisée en chaîne : RFC 7519 §4.1.3), `sub`=client_id, `scope`=`"stats:read articles:write"`, `iat`, `exp`=`iat` + `STATS_TOKEN_TTL_MINUTES`, `jti` (UUID) |
| Vérification | signature HS256 (seul algorithme accepté), `exp` / `nbf` avec 30 s de tolérance, `iss`, `aud` |

Côté Symfony : garder le jeton en cache jusqu'à `expires_in` moins une marge (par exemple 60 s) et n'en
redemander un qu'à l'expiration ou sur un `401`.

## Exemples `curl`

Avec `API` et `TOKEN` définis comme ci-dessus.

### Synchronisation des articles (Symfony, scope `articles:write`)

```bash
# Crée ou met à jour l'article 42 → 204
curl -i -X PUT "$API/api/articles/42" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"Débuter avec Spring Boot","createdAt":"2026-09-01T08:00:00+02:00"}'

# Suppression logique (stats conservées, exclu du top) → 204
curl -i -X DELETE "$API/api/articles/42" -H "Authorization: Bearer $TOKEN"
```

### Événements (front du blog, public)

```bash
# Vue → 202 (dédoublonnée : 1 par session et article toutes les 30 min)
curl -i -X POST "$API/api/events/view" -H "Content-Type: application/json" \
  -d '{"articleId":42,"sessionId":"3f1c2a9e-7b1d-4c55-9a51-2f0e8f3b6d10"}'

# Lecture (envoyée en sortie de page) → 202
curl -i -X POST "$API/api/events/read" -H "Content-Type: application/json" \
  -d '{"articleId":42,"sessionId":"3f1c2a9e-7b1d-4c55-9a51-2f0e8f3b6d10","timeSpentSeconds":185,"scrollPercent":94}'
```

Un événement sur un `articleId` inconnu est accepté (`202`) mais ignoré.

### Statistiques (Symfony, scope `stats:read`)

```bash
# Un article ; period = 24h | 7d | 30d | 90d | all (défaut all) → 200, 404 si article inconnu
curl -s "$API/api/stats/articles/42?period=30d" -H "Authorization: Bearer $TOKEN"

# Top ; period (défaut 7d), limit 1–50 (défaut 5) ; articles supprimés exclus
curl -s "$API/api/stats/top?period=7d&limit=5" -H "Authorization: Bearer $TOKEN"

# Vues par jour ; to (défaut aujourd'hui), from (défaut to − 29 j), 366 jours max,
# articleId facultatif (absent = tout le blog) ; chaque jour présent, même à 0
curl -s "$API/api/stats/trends?from=2026-09-01&to=2026-09-30&articleId=42" -H "Authorization: Bearer $TOKEN"
```

### Supervision

```bash
curl -s "$API/actuator/health"                                   # public → {"status":"UP"}
curl -s "$API/actuator/metrics" -H "Authorization: Bearer $TOKEN" # scope stats:read
```

## Sécurité et erreurs

| Route | Accès |
| --- | --- |
| `/api/events/**` | public, CORS (origines de `STATS_CORS_ORIGINS`, POST, en-tête `Content-Type`, sans cookie), limité en débit |
| `/api/auth/token` | public (le client s'y authentifie par son secret, comparé en temps constant), 10 demandes / min / IP, pas de CORS |
| `/api/stats/**` | JWT avec le scope `stats:read`, pas de CORS |
| `/api/articles/**` | JWT avec le scope `articles:write`, pas de CORS |
| `/actuator/health`, `/actuator/info`, `/swagger-ui.html`, `/v3/api-docs` | public |
| `/actuator/metrics` | JWT avec le scope `stats:read` (les métriques révèlent des détails internes) |
| toute autre route | `404` (même avec un jeton valide) |

L'API est sans état : pas de session, pas de cookie, pas d'utilisateur. Le jeton n'est lu que dans l'en-tête
`Authorization` et seulement sur les routes protégées : un en-tête `Bearer` quelconque n'affecte pas les routes
publiques.

Toutes les erreurs suivent la RFC 9457 (`Content-Type: application/problem+json`), sans stack trace :

```json
{ "type": "about:blank", "title": "Unauthorized", "status": 401, "detail": "Missing access token", "instance": "/api/stats/top" }
```

| Code | Cas |
| --- | --- |
| 400 | paramètre invalide (`period` inconnu, `from > to`, `limit` hors bornes, corps invalide), URL refusée par le pare-feu de Spring Security (`..`, `//`, `;jsessionid`, `%2e`…) ; sur `/api/auth/token` : `invalid_request`, `unsupported_grant_type`, `invalid_scope` (propriété `error`) |
| 401 | jeton absent (`detail` « Missing access token »), invalide, expiré, mal signé, mauvais `iss` / `aud` (« Invalid or expired access token », `error` = `invalid_token`), avec `WWW-Authenticate: Bearer realm="stats-api"…` ; sur `/api/auth/token` : `invalid_client` avec `WWW-Authenticate: Basic realm="stats-api"` |
| 403 | jeton valide sans le scope requis (`detail` « Insufficient scope », `error` = `insufficient_scope`) ; requête CORS refusée (origine, méthode ou en-tête non autorisés) |
| 404 | article inconnu, route inexistante |
| 415 | `Content-Type` autre que `application/json` ou `text/plain` sur `/api/events/**`, autre que `application/x-www-form-urlencoded` sur `/api/auth/token` |
| 429 | plus de 60 événements / min, ou plus de 10 demandes de jeton / min, pour une même IP |
| 500 | erreur interne |

Les erreurs levées hors de Spring MVC (filtres, conteneur de servlets) passent aussi par un `ErrorController`
qui répond en RFC 9457 ; `/error` n'est pas appelable directement (`404`).

### Limitation de débit

`POST /api/events/**` : 60 requêtes / minute / IP (Bucket4j, fenêtre fixe : 60 jetons rechargés d'un coup une
minute après le premier événement de l'IP). Le 61ᵉ événement d'une même minute reçoit `429 Too Many Requests`
avec l'en-tête `Retry-After` (secondes avant la fin de la fenêtre). Les pré-requêtes CORS `OPTIONS` ne
comptent pas. L'en-tête `X-RateLimit-Remaining` indique le quota restant. Quota réglable avec
`STATS_RATELIMIT_EVENTSPERMINUTE` (`0` = désactivé).

`POST /api/auth/token` : même mécanisme, 10 demandes / minute / IP, réussies ou non (pas de recherche
exhaustive d'un secret client). Réglable avec `STATS_RATELIMIT_TOKENREQUESTSPERMINUTE`.

L'IP est `request.getRemoteAddr()`. Avec `server.forward-headers-strategy: native`, Tomcat (`RemoteIpValve`)
la remplace par l'IP lue dans `X-Forwarded-For` **seulement si la connexion vient d'un proxy de confiance** :
`server.tomcat.remoteip.internal-proxies`, variable `STATS_TRUSTED_PROXIES` (regex Java sur l'IP). Par défaut
seule la boucle locale (`127.x.x.x`, `::1`) est de confiance : un client qui envoie son propre
`X-Forwarded-For` depuis une autre adresse est ignoré (le défaut de Tomcat, qui croit tous les réseaux privés
`10/8`, `172.16/12`, `192.168/16`, permettrait de changer d'IP apparente à chaque requête depuis le réseau Docker).

En production, derrière nginx ou Caddy, `STATS_TRUSTED_PROXIES` doit désigner **l'IP sous laquelle le proxy
joint l'API** : par exemple la passerelle du réseau Docker (`docker network inspect blog-stats_default`,
champ `Gateway`, soit `STATS_TRUSTED_PROXIES=172\.18\.0\.1`) quand un nginx de l'hôte relaie vers le port
publié, ou l'IP du conteneur du proxy s'il est dans le même réseau Docker. Le proxy transmet l'IP du client (`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` avec
nginx, comportement par défaut de Caddy) ; Tomcat retient la dernière IP non fiable de la liste. Si la variable
est mal réglée, tous les clients partagent le quota du proxy et reçoivent des `429` en masse.

## Configuration

| Variable d'environnement | Propriété | Défaut | Rôle |
| --- | --- | --- | --- |
| `DB_URL` | `spring.datasource.url` | `jdbc:mariadb://localhost:3307/stats` (compose : `stats-db:3306`) | URL JDBC |
| `DB_USER` | `spring.datasource.username` | `stats` | utilisateur MariaDB |
| `DB_PASSWORD` | `spring.datasource.password` | `stats` | mot de passe MariaDB |
| `DB_ROOT_PASSWORD` | – (compose) | `root` | mot de passe root MariaDB |
| `DB_PORT` / `API_PORT` | – (compose) | `3307` / `8080` | ports publiés sur l'hôte |
| `STATS_CLIENT_ID` | `stats.auth.clients[0].id` | `symfony-blog` | identifiant de l'application Symfony |
| `STATS_CLIENT_SECRET` | `stats.auth.clients[0].secret` | `change-me` | secret de l'application Symfony (**à changer**) |
| `STATS_JWT_SECRET` | `stats.auth.jwt-secret` | `dev-only-jwt-secret-change-me-0123456789` | clé HMAC de signature des JWT, ≥ 32 octets, connue de l'API seule (**à changer**) |
| `STATS_TOKEN_TTL_MINUTES` | `stats.auth.token-ttl-minutes` | `15` | durée de vie d'un jeton |
| `STATS_CORS_ORIGINS` | `stats.cors.allowed-origins` | `http://localhost:8000,http://127.0.0.1:8000,https://127.0.0.1:8000` | origines du blog, séparées par des virgules |
| `SPRING_PROFILES_ACTIVE` | – | vide | `demo` : données de démonstration ; `local` : H2 |
| `STATS_RATELIMIT_EVENTSPERMINUTE` | `stats.rate-limit.events-per-minute` | `60` | quota d'événements par IP (`0` = désactivé) |
| `STATS_RATELIMIT_TOKENREQUESTSPERMINUTE` | `stats.rate-limit.token-requests-per-minute` | `10` | quota de demandes de jeton par IP (`0` = désactivé) |
| `STATS_TRUSTED_PROXIES` | `server.tomcat.remoteip.internal-proxies` | `127\.\d+\.\d+\.\d+\|0:0:0:0:0:0:0:1\|::1` | regex des IP de reverse proxy dont `X-Forwarded-For` est cru |

## Mise en production

Les valeurs par défaut servent au `docker compose up` sans configuration ; avant d'exposer l'API :

- [ ] `STATS_CLIENT_SECRET` : secret aléatoire (`openssl rand -hex 32`), le même côté Symfony ; `STATS_CLIENT_ID`
  à la convenance, identique des deux côtés.
- [ ] `STATS_JWT_SECRET` : clé aléatoire d'au moins 32 octets (`openssl rand -base64 48`), **connue de l'API
  seule** (Symfony ne vérifie pas les jetons). Plus courte, l'API refuse de démarrer ; la changer invalide les
  jetons en cours (Symfony en redemande un au premier `401`).
  Avec la clé ou le secret client par défaut (ou un secret client de moins de 16 caractères), l'API écrit une
  alerte `ERROR` « INSECURE AUTH SETTINGS » au démarrage.
- [ ] `STATS_TRUSTED_PROXIES` : IP (regex) du reverse proxy, voir [Limitation de débit](#limitation-de-débit).
- [ ] `STATS_CORS_ORIGINS` : origine(s) HTTPS exacte(s) du blog, par exemple `https://blog.example.com`
  (retirer les origines `localhost` / `127.0.0.1:8000` de développement).
- [ ] `DB_PASSWORD` et `DB_ROOT_PASSWORD` : mots de passe forts (avant le premier démarrage : ils sont figés
  dans le volume `stats-db-data` à sa création).
- [ ] Reverse proxy HTTPS (nginx, Caddy) devant l'API ; ne pas ouvrir le port 8080 au public
  (par exemple `API_PORT=127.0.0.1:8080` pour ne le publier que sur la boucle locale). MariaDB n'est publiée
  que sur `127.0.0.1`.

## Limites connues

- **Lecteurs uniques agrégés** : dans `/api/stats/trends` et `/api/stats/top`, `uniqueReaders` additionne les
  sessions distinctes de chaque article et de chaque jour. Un lecteur de 3 articles compte 3 dans la tendance
  du blog entier, un lecteur venu 2 jours compte 2. `/api/stats/articles/{id}` compte, lui, les sessions
  distinctes exactes sur la période.
- **Rétention** : les événements bruts sont purgés après 13 mois, les agrégats journaliers sont conservés.
  `/api/stats/top?period=all` inclut donc tout l'historique, alors que `/api/stats/articles/{id}?period=all`
  (calculé sur les événements bruts) ne couvre que les 13 derniers mois.
- **Limitation de débit par IP uniquement** (pas par session) : des lecteurs derrière la même IP (NAT
  d'entreprise, réseau mobile) partagent les 60 événements / min.
- **Cache** : les statistiques sont mises en cache 5 min ; un événement peut mettre jusqu'à 5 min à apparaître.
- **`/api/stats/trends`** : si seul `to` est fourni, `from` vaut `to − 29` jours (30 jours inclus).
