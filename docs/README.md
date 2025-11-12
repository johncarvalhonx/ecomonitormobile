# EcoMonitor

EcoMonitor é um projeto simples de monitoramento ambiental formado por:

- **Aplicativo Android (mobile)**: consulta e exibe as condições ambientais de um local.
- **API em Python/FastAPI (backend)**: busca dados em serviços externos gratuitos e devolve tudo pronto para o app.

O objetivo é mostrar, de forma rápida e clara, informações sobre clima, qualidade do ar e riscos de chuva/alagamento usando tecnologias acessíveis e sem necessidade de chave de API.

[Vídeo do programa em funcionamento (YouTube)](https://youtu.be/fdQrvyRnSWs)

---

## 1. Funcionalidades

### App Android

- Buscar cidade com **autocomplete** (insensível a acentos).
- Usar **localização atual** do dispositivo.
- Digitar manualmente **latitude** e **longitude**.
- Exibir um **dashboard** com:
  - Temperatura atual.
  - Qualidade do ar:
    - AQI (índice numérico).
    - PM2.5 e PM10.
    - Indicador de estado (Very Good → Very Bad) com cores.
  - Chuva nas próximas 6 horas:
    - Maior probabilidade.
    - Total estimado.
  - Riscos:
    - Risco de chuva intensa.
    - Risco de qualidade do ar ruim.
    - Risco de alagamento.
- Botão para mostrar/ocultar o **JSON bruto** retornado pela API (modo técnico).

### Backend (Eco Monitor API)

- **/status**: checa se a API está no ar.
- **/places**: retorna sugestões de locais para o autocomplete.
- **/raw/openmeteo**: traz os dados originais das APIs externas (uso técnico).
- **/environment**: endpoint principal; retorna um resumo único com:
  - dados atuais,
  - próximos horários,
  - próximos dias (vazão de rios),
  - níveis de risco prontos para exibir no app.

A API usa as APIs públicas do **Open-Meteo** (tempo, qualidade do ar e flood) e **não exige chave de API**.

---

## 2. Estrutura do repositório

Sugerida (ajuste conforme o projeto do grupo):

```text
/
├── mobile/
│   ├── app/src/...
│   └──build.gradle
├── backend/
│   ├── main.py
│   └── requirements.txt
└── docs/       
    └── README.md
```

---

## 3. Como rodar o backend (API)

### Pré-requisitos

- Python 3.10 ou superior
- Pip instalado
- Acesso à internet (para chamar o Open-Meteo)

### Passos

Dentro da pasta `backend/`:

```bash
python -m venv .venv
# Windows:
.venv\Scripts\activate
# Linux/Mac:
source .venv/bin/activate

pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

A API ficará disponível em:

- `http://localhost:8000` (no computador)
- `http://10.0.2.2:8000` (quando acessada pelo emulador Android)

Documentação automática (Swagger):

- `http://localhost:8000/docs`

---

## 4. Como rodar o app Android

### Pré-requisitos

- Android Studio
- Emulador Android ou dispositivo físico
- Backend rodando (veja passo anterior)

### Configuração do endpoint

No código do app (ex.: `MainActivity` ou arquivo de configuração):

- Para **emulador**:
  ```kotlin
  const val BASE_URL = "http://10.0.2.2:8000"
  ```
- Para **dispositivo físico na mesma rede**:
  ```kotlin
  const val BASE_URL = "http://SEU_IP_LOCAL:8000"
  ```
  Exemplo: `http://192.168.0.10:8000`

### Passos

1. Abra o Android Studio.
2. Importe o projeto da pasta `mobile/`.
3. Confirme a `BASE_URL` correta.
4. Rode o app em um emulador ou aparelho físico.

---

## 5. Fluxo básico de uso

1. Inicie o backend (API Eco Monitor).
2. Abra o app EcoMonitor.
3. Escolha um local:
   - digite o nome da cidade e selecione na lista, ou
   - use o botão de localização atual, ou
   - informe lat/lon manualmente.
4. Toque em **Consultar**.
5. Veja no painel:
   - temperatura,
   - qualidade do ar e faixa de cor,
   - chuva nas próximas 6 horas,
   - riscos principais.
6. (Opcional) Clique em "Show raw" para ver o JSON retornado pela API.

---

## 6. Tecnologias usadas

**Mobile**

- Kotlin
- Android Views/XML
- Material Design 3
- OkHttp (HTTP)
- Gson (JSON)
- FusedLocationProviderClient (localização)

**Backend**

- Python
- FastAPI
- Uvicorn
- httpx
- Pydantic
- Open-Meteo APIs (tempo, ar, flood)

---

## 7. Autores

Desenvolvido por João Carvalho.

---

## 8. Licença

Este projeto é de uso acadêmico.

