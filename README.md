# Gestor Comprador (Android)

Aplicativo Android nativo (Kotlin) para o **comprador** logar no sistema ERP Gestor e **persistir sua localização GPS** no backend, inclusive com o app em segundo plano.

## Funcionalidades

- **Login** via `POST /api/login` (JWT de 24h) — usuário, senha e URL do servidor.
- **Rastreamento GPS em segundo plano** (Foreground Service):
  - Obtém localização via GPS/rede (`LocationManager`).
  - Envia a **posição atual** para `POST /api/buyer-tracking` a cada 30s.
  - Registra **eventos no histórico** (`POST /api/buyer-tracking-events`) quando há movimento ≥ 50m.
- **Viagem de compras**: iniciar/finalizar viagem via `POST /api/buyer-trips` (para métricas de tempo médio de retorno).
- **Notificação permanente** enquanto o rastreamento está ativo.
- **Bateria**: solicita isenção de otimização de bateria para manter o GPS em segundo plano.

## Permissões Android

- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — GPS.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` — serviço em primeiro plano com tipo `location` (necessário no Android 14+).
- `POST_NOTIFICATIONS` — notificação do serviço (Android 13+).
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — manter o app vivo em segundo plano.

## Endpoints consumidos

| Endpoint | Uso |
|----------|-----|
| `POST /api/login` | Autenticação → `{ token, id, name, role }` |
| `POST /api/buyer-tracking` | Atualiza a posição atual do comprador (upsert) |
| `POST /api/buyer-tracking-events` | Registra evento de posição no histórico |
| `POST /api/buyer-trips` | Inicia/finaliza uma viagem de compras |

> A autenticação usa o header `Authorization: Bearer <token>` (o backend também aceita `x-auth-token`).

## Como compilar

1. Abra a pasta `gestor-android` no **Android Studio** (recomendado).
2. Aguarde o **Gradle Sync** (baixa dependências e SDK components).
3. Conecte um aparelho Android (ou use emulador) e clique em **Run**.

Ou via terminal (com JDK 17 e Android SDK configurados):

```bash
./gradlew assembleDebug
# APK gerado em: app/build/outputs/apk/debug/app-debug.apk
```

### Configuração necessária

- **Android SDK**: compileSdk 34 (instalar via Android Studio SDK Manager).
- **JDK 17**: o Android Studio já inclui (JBR) em `C:\Program Files\Android\Android Studio\jbr`.
- O SDK será baixado automaticamente no primeiro sync, se necessário.

## Configuração do servidor

Na primeira execução, o app pede a **URL do sistema** (ex.: `http://192.168.0.100:3000`). O aparelho precisa alcançar o servidor pela rede (mesma rede local ou VPS com HTTPS).

> **Importante:** em um celular físico, `localhost` aponta para o próprio celular. Use o IP da máquina na rede local ou a URL pública da VPS.

## Repositório

Este projeto é **independente** do repositório principal do ERP — crie um repositório GitHub próprio para ele.
