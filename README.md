# SB24 Sim API

Simulação **educativa** de um backend de banca de retalho (inspirado nas funcionalidades públicas da app SB24 do
Standard Bank de Angola: saldos/extractos, transferências e pagamentos de serviços). Sem ligação a nenhum banco: o
código de banco `9944` é fictício e nenhum dado é real.

**Stack:** Java 17 · Spring Boot 3.3 (Web MVC, Data JPA/Hibernate, Security, OAuth2 Resource Server) · Flyway ·
PostgreSQL (H2 em ficheiro em dev) · Redis (Lettuce) · fila durável em base de dados · Bucket4j · Argon2id ·
springdoc/OpenAPI · JUnit 5 (76 testes) · Docker · Jenkins.

## Correr

```bash
# desenvolvimento: H2 em ficheiro (./data, persiste), chave JWT cifrada gerada em ./data, Swagger, dados de demonstração
SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8099 ./mvnw spring-boot:run     # Swagger: http://localhost:8099/swagger-ui.html
./mvnw test                                                           # 76 testes

# stack completa: PostgreSQL + Redis + API (volumes persistentes)
cp .env.example .env     # preencher; ver comentários no ficheiro
docker compose up --build
```

Contas de demonstração (só perfil `dev`; `rm -rf data` para recomeçar):

| Tipo | Utilizador | Palavra-passe |
|---|---|---|
| Cliente | `ana@`, `bruno@`, `carla@`, `diogo@example.ao` | `Demo#Passw0rd2026` |
| Admin | `admin@example.ao` (SUPER_ADMIN), `operador@example.ao` (OPERATOR), `auditor@example.ao` (AUDITOR) | `Admin#Passw0rd2026` |
| Credor (API) | client id `epal-demo` / `ende-demo` | `Creditor#Secret2026-demo-only` |

## Três realms de identidade

| | Clientes | Back-office | Credores (parceiros) |
|---|---|---|---|
| Tabela | `app_user` | **`admin_user`** | **`partner`** |
| Login | `POST /auth/login` | `POST /admin/auth/login` | `POST /partner/auth/token` (client id + secret) |
| Token | RS256, 10 min, `realm=customer`, refresh rotativo | RS256, 10 min, `realm=admin`, sem refresh | RS256, 10 min, `realm=partner`, sem refresh |
| Prefixos | `/me /accounts /transfers /payments /notifications /beneficiaries /savings /direct-debits` | `/admin/**` | `/partner/**` |

Um token de um realm **não** abre as portas de outro (testado nos três sentidos).

## Controlo de acesso: RBAC + ABAC, sempre consultado na base de dados

**Princípio:** o token só prova *quem* és e *de que realm*. O que podes fazer é **consultado na BD em cada pedido** (papéis → permissões, cache de 30 s, invalidada na hora quando se muda um papel). Revogar um papel tem efeito imediato com o mesmo token.

```
pedido → [Security: autenticado? realm certo?] → [PermissionInterceptor: @RequiresPermission, RBAC na BD]
       → [serviço: PolicyService.require(sujeito, permissão, recurso) = RBAC + todas as políticas ABAC]
       → só então executa a operação
```

**RBAC** (tabelas `permission`, `access_role`, `role_permission`, `subject_role`; 31 permissões, 6 papéis):

| Papel | Realm | Para quê |
|---|---|---|
| `CUSTOMER` | cliente | acesso completo à própria conta |
| `CUSTOMER_VIEWER` | cliente | só consulta (não movimenta dinheiro) |
| `SUPER_ADMIN` | admin | todas as permissões de admin (cria admins, papéis, credores) |
| `OPERATOR` | admin | bloqueios, congelamentos, dados pessoais, conciliação, fila |
| `AUDITOR` | admin | só leitura, **sem** dados pessoais |
| `CREDITOR` | credor | submeter e consultar cobranças de débito directo |

**Fail-closed:** cada handler de `/api/v1` tem de declarar `@RequiresPermission("X")` ou `@PublicEndpoint`; sem nenhum dos dois o pedido é recusado (`NO_POLICY_DEFINED`), e o resto do espaço de URLs é `denyAll`. Um teste (`PermissionCoverageIT`) percorre todos os endpoints e **falha o build** se algum não declarar permissão, ou se uma permissão não existir no catálogo / não for atribuída a nenhum papel.

**ABAC** (`PolicyService` + políticas nomeadas, *deny-overrides*; cada recusa é auditada como `ACCESS_DENIED` com a política e o motivo):

| Política | Regra |
|---|---|
| `OwnershipPolicy` | um cliente só toca nos seus recursos; recurso alheio responde **404** (sem oráculo de existência) mas fica auditado |
| `AccountUsablePolicy` | dinheiro só sai de conta `ACTIVE` e do tipo certo (poupança não é conta à ordem) |
| `NewBeneficiaryCoolingOffPolicy` | beneficiário com menos de 24 h só recebe transferências ≤ 100 000 AOA |
| `HighValueDevicePolicy` | transferências ≥ 500 000 AOA só a partir de um dispositivo já usado no login (atributo de ambiente) |
| `SavingsLockPolicy` | depósito a prazo bloqueado até ao vencimento |
| `PartnerScopePolicy` | um credor só vê mandatos/cobranças do seu próprio credor (nem um irmão do mesmo credor vê as do outro) |
| `MandateLimitPolicy` | uma cobrança nunca excede o máximo autorizado no mandato |
| `SeparationOfDutiesPolicy` | quem bloqueou um cliente (OPERATOR) não o pode desbloquear (quatro olhos; SUPER_ADMIN isento) |
| `SelfModificationPolicy` | ninguém altera os seus próprios papéis |

A gestão de papéis também é permissionada: `GET /admin/rbac`, `GET /admin/subjects/{id}/access`, `POST /admin/subjects/{id}/roles` e `/roles/revoke` (exigem `reason`; o papel tem de ser do mesmo realm do utilizador). O Swagger mostra, em cada operação, a permissão exigida (`x-permission`).

## API (`/api/v1`) — documentada em `/swagger-ui.html` (só dev) e `/v3/api-docs`

| Método | Rota | Descrição |
|---|---|---|
| POST | `/auth/register` `/auth/login` `/auth/refresh` `/auth/logout` | Registo, sessão, refresh rotativo, logout com revogação imediata |
| GET | `/.well-known/jwks.json` | Chave pública (JWKS) |
| GET | `/me` `/accounts` `/accounts/{id}` `/accounts/{id}/transactions` | Perfil, contas (**saldo contabilístico / cativo / disponível**), extracto |
| POST | `/transfers` | Transferência: interna imediata; **interbancária assíncrona** (cativo + liquidação pela fila) |
| GET | `/transfers/{ref}` | Estado: `PENDING` → `SETTLED` / `REJECTED` |
| GET/POST | `/payments/providers` `/payments` | Pagamento de serviços (entidades vêm da BD) |
| GET | `/notifications` | Notificações geradas pela fila |
| GET/POST/DELETE | `/beneficiaries` | Beneficiários guardados (IBAN validado, nome cifrado, titular real mascarado para confirmar); `POST /transfers` aceita `beneficiaryId` em vez de `toIban` |
| GET/POST | `/savings/products` `/savings` `/savings/{id}/deposit\|withdraw` | Poupança flexível e depósitos a prazo (juros diários) |
| GET/POST | `/direct-debits` `/{id}/pause\|resume\|cancel` `/{id}/collections` | Mandatos de débito directo do cliente |
| POST | `/partner/auth/token` | Token do credor |
| POST/GET | `/partner/collections` `/{id}` | O credor submete cobranças (202, assíncronas, idempotentes por `externalRef`) e consulta as suas |
| POST | `/admin/auth/login` | Login de administrador |
| GET/POST | `/admin/rbac` `/admin/subjects/{id}/access\|roles\|roles/revoke` | Catálogo de papéis, acesso efectivo, atribuir/revogar |
| GET/POST | `/admin/partners` | Criar/listar credores (segredo mostrado uma só vez) |
| GET | `/admin/audit?actorId&action` | Rasto de auditoria |
| GET | `/admin/customers` `/admin/customers/{id}` | Clientes (nomes mascarados; consultar dados pessoais é auditado) |
| POST | `/admin/customers/{id}/block\|unblock`, `/admin/accounts/{id}/freeze\|unfreeze` | Bloquear/congelar (com `reason`) |
| GET/POST | `/admin/admins` | Listar / criar administradores (criar: SUPER_ADMIN) |
| GET/POST | `/admin/reconciliation/runs` `/runs/{id}` `/run?date=` | Histórico, detalhe e conciliação manual |
| GET/POST | `/admin/queue/stats` `/dead` `/{id}/requeue` | Fila e dead-letters |
| POST | `/admin/integrity-check` | Verifica cadeia do livro razão e MACs do rasto |

`/transfers` e `/payments` exigem `Idempotency-Key`. Valores são **números JSON com no máximo 2 casas decimais**.

## Rasto de auditoria: tudo o que se faz fica registado

Tabela **`audit_log`** (uma linha por acção, de quem for):

| Campo | Conteúdo |
|---|---|
| `actor_type`, `actor_id` | `CUSTOMER`, `ADMIN`, `SYSTEM` (workers/crons) ou `ANONYMOUS` + id do autor |
| `action`, `resource_type`, `resource_id`, `outcome` | o quê, sobre quê, `SUCCESS`/`DENIED`/`FAILED`/`THROTTLED` |
| `ip`, `device_id`, `user_agent`, `request_id` | **origem do pedido** (ver abaixo) |
| `http_method`, `path`, `http_status` | linha de rasto genérica de cada chamada |
| `detail`, `created_at`, `mac` | contexto (nunca segredos) e HMAC da linha (detecta adulteração) |

Três camadas:
1. **Rasto de cada pedido** (`RequestTrailFilter`, o filtro mais externo): *todas* as chamadas à API, de clientes, admins ou anónimos, incluindo as recusadas por autenticação (401/403) ou rate limit (429). A query string nunca é guardada.
2. **Eventos de negócio** com recurso e resultado: `LOGIN`, `NEW_DEVICE_LOGIN`, `TRANSFER`, `PAYMENT`, `TRANSFER_SETTLED`, `ACCOUNT_LOCK`, `REFRESH_REUSE`…
3. **Acções de admin** (`ADMIN_LOGIN`, `ADMIN_VIEW_CUSTOMER`, `ADMIN_BLOCK_CUSTOMER`, `ADMIN_FREEZE_ACCOUNT`, `ADMIN_CREATE_ADMIN`, `ADMIN_REQUEUE_MESSAGE`, `ADMIN_RUN_RECONCILIATION`, `ADMIN_VIEW_AUDIT`…) com o admin autor, o alvo e a justificação. **Ler** dados pessoais também é uma acção auditada.

**IP, dispositivo e user agent:** o `RequestContextFilter` guarda-os num contexto por pedido, e cada linha de auditoria, escrita onde for, herda-os. IP = endereço do socket (`X-Forwarded-For` só se `trust-forward-headers`, e então só a entrada mais à direita). Dispositivo = cabeçalho `X-Device-Id` (aceite só se tiver formato válido `[A-Za-z0-9._-]{8,64}`). User agent truncado a 255 caracteres. Workers e crons não têm contexto, logo ficam como `SYSTEM`. Tabela `known_device`: um login a partir de um dispositivo novo gera `NEW_DEVICE_LOGIN` e uma notificação ao cliente.

A verificação de integridade (cron 02:00 ou `POST /admin/integrity-check`) recalcula também os MACs de `audit_log`.

## Poupança

Produtos (seed): `FLEX` 4 %/ano (levantamento livre, mín. 1 000), `TERM90` 8 % (90 dias, mín. 50 000), `TERM180` 10 % (180 dias, mín. 100 000). Cada poupança é uma **conta real do razão** (`SAVINGS`, IBAN próprio); subscrever, depositar e levantar movem dinheiro com lançamentos de dupla entrada, sempre com `Idempotency-Key`. Depósitos a prazo ficam bloqueados até ao vencimento (`SavingsLockPolicy`). **Juros diários** (saldo × taxa ÷ 365, arredondamento bancário) pagos pela conta interna `INTEREST`, via fila às 00:15 de Luanda, **idempotentes por conta e dia** (`savings_interest`). Uma poupança não pode ser origem/destino de transferências nem de pagamentos.

## Beneficiários

`POST /beneficiaries` valida o IBAN (mod-97), limita a 50 por cliente, cifra o nome em repouso, devolve o **titular real mascarado** para IBANs do próprio banco (para o cliente confirmar quem vai pagar) e notifica o cliente. Nas primeiras 24 h aplica-se o limite de arrefecimento (ABAC). Os beneficiários de outro cliente são invisíveis (404).

## Débito directo

1. O **cliente** autoriza um *mandato*: credor, referência (validada pelo padrão do credor), valor máximo por cobrança e frequência (semanal/mensal/anual). Pode pausar, retomar ou cancelar (cancelar é definitivo).
2. O **credor** (realm `partner`) submete *cobranças* com `externalRef` (idempotente: repetir devolve a mesma; mudar os dados dá 409). A pesquisa do mandato já é restrita ao credor do parceiro, e o ABAC recusa valores acima do máximo (403) e datas fora de [ontem, +5 dias].
3. A cobrança entra na fila (`DIRECT_DEBIT`) e o worker decide: **PAID** (lançamento `DIRECT_DEBIT`) ou **RETURNED** (sem saldo disponível, conta congelada, limite diário) / **REJECTED** (mandato pausado/cancelado, período já cobrado). O cliente é notificado em cada passo e o credor consulta o estado.

## Saldo contabilístico e saldo disponível

| | Significado |
|---|---|
| `balance` | **Saldo contabilístico**: o que o livro razão diz |
| `heldAmount` | **Cativo**: reservas activas (tabela `account_hold`) |
| `availableBalance` | **Saldo disponível** = `balance − heldAmount`: só este é gastável |

Transferência interbancária: cria o cativo (disponível desce já, contabilístico não) e um `PendingTransfer`; o worker liquida: **aceite** → lançamentos no razão, cativo capturado; **rejeitada** → cativo libertado. Constraint de BD `balance − held ≥ 0`. O limite diário conta débitos + cativos.

## Filas (processamento assíncrono)

Fila durável na BD (tabela `queue_message`, *transactional outbox*): a mensagem é criada **na mesma transacção** que a alteração de negócio, por isso nunca se perde nem existe para uma operação revertida. O `QueueWorker` (poll de 1 s):
- *claim* atómico por `UPDATE … WHERE` (seguro com várias instâncias), `lease` de 2 min para workers que morrem;
- entrega *at-least-once* com handlers idempotentes; *backoff* exponencial (5 s × 2ⁿ, máx. 5 min); após 5 tentativas → **dead-letter** (auditado; admins listam e reenfileiram);
- chave de deduplicação única (`settle:<ref>`, `recon:<data>`).

| Fila | Faz |
|---|---|
| `SETTLEMENT` | liquida/rejeita transferências interbancárias (gateway simulado: banco `9999` rejeita; `0050` falha 2 vezes e depois aceita) |
| `NOTIFICATION` | entrega notificações (visíveis em `GET /notifications`) |
| `RECONCILIATION` | executa a conciliação de um dia |
| `INTEREST` | credita os juros diários das poupanças |
| `DIRECT_DEBIT` | processa cobranças de débito directo |

## Conciliação automática às 00:00 (hora de Angola)

`@Scheduled(cron = "0 0 0 * * *", zone = "Africa/Luanda")` coloca na fila a conciliação do dia que acabou de fechar (chave `recon:<data>`: uma por dia mesmo com várias instâncias). Se a app estava parada à meia-noite, um *catch-up* no arranque enfileira o dia em falta. A conciliação (`ReconciliationService`) verifica:
1. por conta: abertura (snapshot do dia anterior) + créditos − débitos = fecho no razão; grava o snapshot (`daily_snapshot`) que é a abertura do dia seguinte;
2. cada transacção (`transfer_ref`): débitos = créditos (dupla entrada);
3. soma de todos os saldos de fecho = 0;
4. `held` das contas = soma dos cativos activos; transferências liquidadas têm lançamentos; nenhuma pendente há mais de 24 h;
5. dead-letters na fila.

Resultado em `reconciliation_run` / `reconciliation_item` (`OK`, `OK_WITH_WARNINGS`, `DISCREPANCIES`), consultável por admins; as divergências geram log `ERROR` e auditoria `RECONCILIATION/FAILED`.

## Seeders (arrancam por ordem; todos idempotentes)

| # | Seeder | Perfis | Cria |
|---|---|---|---|
| 1 | `SystemBootstrap` | todos | utilizador SYSTEM (sem login) e contas internas CLEARING, PAYMENTS, FEES, INTEREST |
| 2 | `ReferenceDataSeeder` | todos | entidades de pagamento/débito directo (Unitel, Africell, ENDE, EPAL, DStv) e produtos de poupança |
| 3 | `AccessControlSeeder` | todos | permissões, papéis e concessões por omissão (só insere o que falta: alterações manuais na BD não são revertidas) |
| 4 | `AdminBootstrap` | prod | primeiro SUPER_ADMIN, só se a tabela estiver vazia e `APP_ADMIN_BOOTSTRAP_EMAIL/PASSWORD` estiverem definidos |
| 5 | `DevSeeder` | **dev** | 3 admins, 2 credores, 4 clientes com depósitos e histórico (transferências, pagamentos, uma interbancária liquidada pela fila, beneficiário, 2 poupanças, mandato EPAL) |

## Tríade CIA

| | Controlo |
|---|---|
| **Confidencialidade** | PII (nome, telefone, BI) cifrada com **AES-256-GCM**; *blind index* HMAC para unicidade do BI; palavras-passe **Argon2id**; refresh tokens só como SHA-256; chave privada JWT cifrada em repouso; admins só vêem dados mascarados; respostas nunca ecoam valores rejeitados; HSTS, `no-store`, CORS fechado |
| **Integridade** | JWT **RS256** (algoritmo fixado); razão com **cadeia de HMAC**; rasto de auditoria com **MAC por linha**; dupla entrada; `CHECK` de saldos; locks pessimistas ordenados; idempotência; conciliação diária; validação estrita |
| **Disponibilidade** | Rate limiting, throttling, bulkheads; Redis com *fallback*; fila com retry/dead-letter; `shutdown` gracioso; healthcheck; contentor *read-only* |

### Key stretching
- **Palavras-passe:** Argon2id (19 MiB, 2 iterações, 1 lane: mínimo OWASP).
- **Chave privada RS256 em repouso:** passphrase → **PBKDF2-HMAC-SHA256, 310 000 iterações**, salt aleatório → AES-256-GCM. (RS256 é assimétrico: o *stretching* protege a chave e as passwords, não a assinatura.)

## Validação estrita
Jackson recusa campos desconhecidos, chaves duplicadas, *trailing tokens*, `null` em primitivos e coerção de strings para números (`"10.00"` ≠ `10.00`); decimais via `BigDecimal`, `@Digits(15,2)`, mínimo 0,01; IBAN com checksum mod-97; regex por campo; texto livre só por *allow-list* (`@SafeText`); `page/size` limitados; erros de validação só devolvem nomes de campos.

## Rate limiting e throttling

| Camada | Chave | Limite (`app.rate-limit`) | Resposta |
|---|---|---|---|
| Rede de segurança | IP | 300/min | 429 + `Retry-After` |
| `/auth/**` e `/admin/auth/**` | IP | 10/min | 429 |
| Dinheiro | utilizador | 12/min | 429 |
| Restantes | utilizador | 120/min | 429 |
| Throttling suave | utilizador | <25% de tokens: atraso de 150 ms | 200 mais lento |
| Bulkhead por utilizador | utilizador | 2 operações financeiras em simultâneo | 429 `CONCURRENCY_LIMIT` |
| Bulkhead global | instância | 200 | 503 + `Retry-After` |

## Redis (`app.redis.enabled=true`)
(1) Rate limiting distribuído (script Lua atómico); (2) **denylist de `jti`** (logout revoga o access token de imediato); (3) lock distribuído para os crons de limpeza. Sem Redis, a mesma API usa memória (1 instância); se o Redis falhar, cada instância usa baldes locais.

## Cron jobs (`app.jobs.*`, fuso Africa/Luanda)
| Job | Quando | Faz |
|---|---|---|
| `scheduleDailyReconciliation` | **00:00** | enfileira a conciliação do dia fechado |
| `scheduleInterestAccrual` | 00:15 | enfileira os juros diários das poupanças |
| `verifyLedger` | 02:00 | cadeias do razão, saldos, soma zero e MACs do rasto |
| `purgeIdempotencyRecords` | 03:10 | apaga chaves com mais de 48 h |
| `purgeDoneMessages` | 03:30 | apaga mensagens da fila concluídas há mais de 7 dias |
| `purgeRefreshTokens` | de hora a hora | apaga refresh tokens expirados |

## OWASP Top 10 (2021)
| | Controlo |
|---|---|
| A01 Broken Access Control | **RBAC na BD em cada pedido + 9 políticas ABAC**; fail-closed (sem permissão declarada = recusado, validado no CI); realms separados; recurso alheio = 404 e auditado; separação de funções; `denyAll` por omissão |
| A02 Cryptographic Failures | AES-256-GCM, Argon2id, RS256, PBKDF2, HMAC; segredos só por ambiente; HSTS |
| A03 Injection (incl. XSS) | JPA parametrizado, allow-lists, JSON-only + `nosniff` + CSP `default-src 'none'` |
| A04 Insecure Design | limites, idempotência, cativos, bulkheads, dupla entrada, conciliação |
| A05 Security Misconfiguration | headers seguros, Swagger só em dev, `actuator` só `health`, sem stack traces, contentor não-root/read-only |
| A06 Vulnerable Components | `./mvnw -Psecurity verify` (Dependency-Check, CVSS ≥ 7 falha) |
| A07 Identification & Auth Failures | lockout (clientes e admins), mensagens genéricas, tempo igualado, rotação de refresh, logout revoga, admins sem refresh |
| A08 Software & Data Integrity | cadeia HMAC no razão, MAC no rasto, JWT assinado, Jackson estrito |
| A09 Logging & Monitoring | `audit_log` completo (IP/dispositivo/UA/requestId), alertas de integridade e conciliação |
| A10 SSRF | sem pedidos de saída com URLs do utilizador |

## Estrutura
```
config/ crypto/ redis/ security/   audit/ (contexto do pedido, filtros de rasto)   queue/ (fila, worker, handlers)
domain/ repo/ service/ web/        jobs/ (crons)        db/migration/ (Flyway)
```

## Limitações conhecidas
- O caminho **Redis real** (Lettuce + Lua) não foi executado contra um servidor Redis neste ambiente; só a versão em memória e o fallback estão testados. Corra `docker compose up` e repita os testes de rate limit.
- A cache de permissões é de 30 s por instância: uma revogação é imediata na instância que a executa e converge nas outras em ≤ 30 s (com Redis pode passar a invalidação por pub/sub).
- Débito directo: o credor é simulado por um cliente da API; não há o ciclo real de pré-aviso/devolução (SEPA D+5) nem ficheiros de compensação.
- Juros: composição simples diária sobre o saldo do momento do cálculo; sem retenção na fonte nem tabela de taxas por escalão.
- O gateway interbancário é simulado; não há ficheiros de extracto de um banco central contra os quais conciliar (a conciliação é interna: razão, cativos, snapshots).
- Sem 2FA/OTP nem *step-up* em transferências grandes; sem MFA para admins (em produção é obrigatório).
- A chave-mestra de dados está num env var; em produção deve vir de um KMS/HSM, com rotação.
- A verificação de integridade e a conciliação leem tudo; para volumes reais devem ser incrementais/particionadas.
- O `audit_log` detecta modificação (MAC por linha) mas não, sozinho, a *remoção* de linhas; para isso exporte-o para armazenamento WORM.
