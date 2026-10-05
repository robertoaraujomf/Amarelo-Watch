# Amarelo Watch

Transmita a tela do celular para qualquer smart TV na mesma rede Wi-Fi.

O app abre um servidor HTTP local e mostra um QR code. Você aponta a câmera
da TV, abre a página no navegador e a tela aparece em tempo real.

## Como funciona

```
Celular (Android 8+)                    Smart TV
┌────────────────────┐                 ┌──────────────────────┐
│ MediaProjection     │   Wi-Fi local   │ Navegador da TV      │
│      ↓              │  ─────────────> │  <img> com MJPEG     │
│ VirtualDisplay      │   HTTP :8080    │                      │
│      ↓              │                 └──────────────────────┘
│ JPEG por software   │
└────────────────────┘
```

- **Captura:** `MediaProjection` → `VirtualDisplay` → `ImageReader` em `RGBA_8888`,
  com o reader sempre do mesmo tamanho da tela virtual.
- **Giro:** ao virar o celular, a `VirtualDisplay` é redimensionada e a
  superfície trocada com `setSurface()`. Reader e tela ficam com as mesmas
  dimensões, então o quadro chega inteiro na TV, sem tarjas pretas.
- **Transporte:** servidor HTTP próprio (sem dependências Android) servindo
  `multipart/x-mixed-replace`, que qualquer navegador exibe nativamente.
- **Vídeo apenas, sem áudio.**
- Escolha de protocolo pensando em **compatibilidade**: MJPEG abre em
  qualquer TV, mesmo com navegador antigo ou bloqueado. H.264/WebRTC seria
  mais eficiente, mas não é aceito em toda parte.

## Compilando

Requer JDK 17 e Android SDK 35.

```bash
./gradlew :app:assembleRelease
```

O APK sai em `app/build/outputs/apk/release/app-release.apk`.

### Assinatura

Credenciais ficam em `app/keystore.properties` (não versionado):

```properties
storeFile=keystore/amarelo.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Sem esse arquivo o build release sai sem assinatura — o app compila e roda
normalmente em debug, mas não vai para a Play Store.

## Testes

```bash
./gradlew :app:testReleaseUnitTest
```

Cobre o protocolo HTTP: HTML do player, JSON do heartbeat, multipart MJPEG,
`snapshot.jpg`, fallback de porta, encerramento limpo e ausência de lixo no
buffer.
## Limitações conhecidas

- **Girar o celular:** funciona a partir do Android 10 (`API 26`), que tem
  `VirtualDisplay.resize()` e aceita `setSurface()`. A stream acompanha o giro
  e o player da TV reajusta a proporção sozinho.
- Android 8–9 (`minSdk 24`) não tem esses métodos, então a stream mantém a
  orientação em que foi iniciada. O app avisa na tela quando detecta isso.
- Sem áudio, por opção de projeto.
- O desempenho real em smart TV não foi medido em hardware de consumo; a
  validação foi feita em emulador com renderização por software.

## Privacidade

A transmissão acontece apenas na sua rede local, via HTTP sem criptografia.
Nenhum dado sai do dispositivo e não há servidor na nuvem. Use apenas em
redes Wi-Fi confiáveis.

## Licença

Uso livre para fins pessoais.
