
# PairFlow: Docker Version

> **Archived branch.** This branch preserves the original Docker-based architecture
> of the project. The current version, deployed on Kubernetes (Minikube), lives on `main`.

## Overview

A microservices-based backend where every service is containerized and runs
independently, following a loosely coupled architecture. All containers
communicate over a dedicated Docker network.

## Architecture

- **Containerized microservices:** each service has its own Dockerfile and runs
  in its own container, so it can be built, deployed, and scaled independently.
- **Custom Docker network:** all containers join one user-defined bridge network
  and reach each other by container/service name (for example
  `http://tinder-service-registry:8761`), so no hardcoded IPs are needed.
- **Service discovery:** a Eureka server registers all services at runtime.
- **Messaging:** Kafka handles asynchronous communication between services.
- **Services:** `<user-service>`, `<service-2>`, `<service-3>`, ...

## Networking

| Traffic | How it works |
|---|---|
| Container to container | Direct over the Docker network, using container names. No published ports needed. |
| Outside to container (browser, Postman) | Requires port publishing (`ports: "8080:8080"`). Without it, nothing outside Docker can reach the container. |

The project runs on **WSL2** with Docker. Published ports are reachable from
Windows via `localhost:<port>`.

## Fault tolerance and reliability

- `<restart policies so crashed containers come back automatically>`
- `<health checks / depends_on so services start in the right order>`
- `<loose coupling: one service failing doesn't take down the others>`
- `<Kafka buffering messages while a consumer is down>`

## Tech stack

Java, Spring Boot, Spring Cloud (Eureka), Kafka, `<database>`, Docker, Docker Compose, WSL2

## Running it

```bash
git clone <repo-url>
cd <repo>
git checkout docker-compose
docker compose up --build
```

- Eureka dashboard: http://localhost:8761
- `<other service URLs>`

## Why this branch exists

This is the first version of the system, built before moving to Kubernetes. It's
kept as a reference for the Docker networking approach. See `main` for the
Kubernetes version.

Detail Preview of the entire app 
𝗦𝗬𝗦𝗧𝗘𝗠 𝗗𝗘𝗦𝗜𝗚𝗡 𝗗𝗜𝗔𝗚𝗥𝗔𝗠 𝗛𝗔𝗡𝗗 𝗗𝗥𝗔𝗪𝗡

<h2 align="center">System Design</h2>

<p align="center">
  <img src="https://github.com/user-attachments/assets/14a8b905-464b-48a8-abc3-6e02353d0883" width="1000">
</p>

## Architecture Overview

Client → **API Gateway** → Eureka lookup → target microservice

### 1. User Service
Handles registration, login and authentication. Uses Spring Security with JWT and BCrypt password hashing (cost factor 12).

### 2. Location Service
- Stores each user's current latitude and longitude in MySQL.
- Indexes user locations in Redis using GeoHash.
- Performs radius-based searches (e.g. within 10 km).
- Returns the list of nearby user IDs to the Orchestration Service.
- Does not cache search results. Caching is handled by the Orchestration Service.

### 3. Orchestration Service
Coordinates the discovery and swipe flow using the API Composition pattern. It calls the Location, User and Match services through Feign clients (discovered via Eureka) and combines the results into one response. Caches feeds in Redis lists to avoid repeated calls.

### 4. Pairing Service
[CHECK: consumes match events from Kafka, saves them to MySQL, and publishes a notification event]

### 5. Match Service
Records swipes in a MySQL table with a composite key (swiper + swiped), so duplicate swipes can't happen. Publishes swipe/match events to Kafka. Uses @Async and @Transactional for non-blocking writes.

### 6. Notification Service
Consumes Kafka events and sends email through AWS SES v2. Uses @RetryableTopic (4 attempts) with a dead-letter topic for failed messages.

### 7. API Gateway
Built on Spring Cloud Gateway (WebFlux). Validates JWTs and routes requests to the right service through Eureka.

### 8. Eureka Service
Service registry. Every service registers here, so none needs hardcoded addresses.

### 9. Kafka Container
Runs Kafka in KRaft mode (no Zookeeper). Carries events between services so they stay decoupled.

### 10. Distributed SQL
[CHECK: e.g. "Each service owns its own MySQL schema (database-per-service), so services never share tables."]

### 11. Docker
Every service runs in a container, and Docker Compose starts the whole system on one shared network, so containers reach each other by service name.

## Role of Redis
- **Geo-indexing:** the Location Service stores coordinates in Redis GEO for fast radius queries.
- **Caching:** the Orchestration Service caches feed results in Redis lists, which cut response times sharply.

## Role of AWS SES
The Notification Service uses **AWS SES v2** to send emails (such as match notifications) asynchronously. Sending is triggered by Kafka events rather than blocking the request, so the swipe and match flow stays fast even if email delivery is slow.

### How it works
1. **Event published:** when a match happens, an event is published to a Kafka topic.
2. **Event consumed:** the Notification Service picks up the event from Kafka, independently of the request that caused it.
3. **Email sent:** the Notification Service builds the email and sends it through SES v2.
4. **Delivered:** the user receives the notification in their inbox (see the output below).

### Output

Proof of a notification email received through SES:

<p align="center">
  <img width="420" alt="Match notification email received via AWS SES" src="https://github.com/user-attachments/assets/69f286d2-80af-4123-b321-80ae17570e47" />
</p>
<p align="center"><em>Match notification email delivered via AWS SES v2</em></p>

# Load Testing

## Login Load Testing
## Performance Optimization: Fixing a Redundant Auth Query

Load testing the login endpoint with k6 surfaced a hidden inefficiency: every request was querying auth_credentials twice — once internally during Spring Security authentication, and again to re-fetch the same user for JWT generation. The fix reused the already-authenticated principal instead of re-querying, cutting the login flow down to a single database call.

| Metric | Before | After |
|--------|--------|-------|
| Avg Latency | 1.00s | 597ms |
| p95 Latency | 1.29s | 804ms |
| Throughput | 20.06 req/s | 24.97 req/s |

## Performance Discovery: Connection Pool Exhaustion and Hashing Overhead Under Stress

Stress testing at higher concurrency (100 VUs) surfaced two additional bottlenecks beyond the query fix: the HikariCP connection pool was exhausting under load, causing requests to queue and eventually time out, and password hashing overhead was adding significant CPU cost per login at high concurrency. Increasing the pool's maximum size and tuning the hashing cost factor resolved both issues.

| Metric | Before | After | Change |
|---|---|---|---|
| Avg Latency | 597ms | 151.01ms | ↓ 74.7% |
| p95 Latency | 804ms | 249.39ms | ↓ 69.0% |
| Throughput | 24.97 req/s | 33.64 req/s | ↑ 34.7% |
| Failure Rate | 0.00% | 0.00% | — |

## User recommendation Load Testing

### Baseline Discovery Load Test: Full Chain Through Orchestration (Cache-Hit Path)

​```mermaid
flowchart TD
    A[User] --> B[API Gateway]
    B --> C[Login]
    C -- Fail --> C
    C -- Pass --> D[Orchestration Service]
    D --> E[Location Service]
    D --> F[Match Service]
    E --> D
    F --> D
    D --> G[Response to User]
​```

Tested at 100 VUs over 3 minutes with weighted geo-clustered test users (dense urban core, sparser suburban/rural zones).

| Metric | Result |
|---|---|
| Avg Latency | 22.61ms |
| p90 Latency | 41.51ms |
| p95 Latency | 58.09ms |
| Max Latency | 1.96s |
| Throughput | 43.25 req/s |
| Failure Rate | 0.00% |

#Complete System Load Test

swiped
img placeholder


Future Expention ideas
