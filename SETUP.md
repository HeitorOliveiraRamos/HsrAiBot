# Setup — máquina nova

O código vai todo pro git, mas algumas coisas são de **ambiente** e não são versionadas
(de propósito): o token, a extensão pgvector do Postgres, os embeddings (que ficam no banco)
e os modelos do Ollama. Este é o passo-a-passo pra deixar o bot funcional do zero.

Funciona em **macOS**, **Linux** (Debian/Ubuntu, Arch, Fedora) e **Windows via WSL2**.
O `./bot.sh` cuida de subir/parar tudo: ele detecta a infra por capacidade
(`brew` → `pg_ctl` → `systemctl`), não por distro, então não tem nada pra configurar.

## 1. Pré-requisitos

| Ferramenta | Por quê | macOS (Homebrew) | Debian/Ubuntu | Arch |
|---|---|---|---|---|
| **JDK 21+** | alvo do bytecode é 21 | `brew install --cask corretto@21` | `apt install openjdk-21-jdk` | `pacman -S jdk21-openjdk` |
| **Maven** | build | `brew install maven` | `apt install maven` | `pacman -S maven` |
| **PostgreSQL 14+** | banco + vector store | `brew install postgresql@18 && brew services start postgresql@18` | `apt install postgresql` | `pacman -S postgresql` |
| **pgvector** | extensão de vetores (passo 3) | `brew install pgvector` | `apt install postgresql-<versão>-pgvector` | `pacman -S pgvector` |
| **Ollama** | LLM + embeddings | `brew install ollama && brew services start ollama` | `curl -fsSL https://ollama.com/install.sh \| sh` | `pacman -S ollama` |
| **Docker** | só pro web search (SearXNG) | `brew install colima docker docker-compose` | `apt install docker.io docker-compose-v2` (nativo, sem colima) | `pacman -S docker docker-compose` |

> **Fedora/RHEL:** `dnf install java-21-openjdk-devel maven postgresql-server pgvector ollama docker`.
>
> **Arch:** o pacote não cria o cluster. Antes do primeiro start, rode uma vez
> `sudo -u postgres initdb --locale=C.UTF-8 -E UTF8 -D /var/lib/postgres/data` e
> `sudo systemctl enable --now postgresql`. Sem isso o `./bot.sh start` não acha data dir.
>
> **Ollama no Linux** costuma virar serviço do systemd, com os modelos em `/var/lib/ollama` —
> o `ollama pull` do passo 4 tem que rodar como o mesmo usuário do serviço
> (`sudo -u ollama ollama pull ...`) ou o bot não enxerga os modelos.

### Windows

Use o **WSL2** (`wsl --install -d Ubuntu`) e siga a coluna Debian/Ubuntu deste guia dentro
dele — lá o script roda inteiro, sem adaptação. Clone o repo no filesystem do Linux
(`~/hsr-bot`, não `/mnt/c/...`) ou o build do Maven fica lento demais.

O Docker Desktop com "WSL integration" ligada resolve o SearXNG. Se o Ollama já roda no
Windows nativo, não instale outro: aponte o `.env` pro host
(`OLLAMA_BASE_URL=http://$(ip route show default | awk '{print $3}'):11434`) e ligue
`OLLAMA_HOST=0.0.0.0` no serviço do Windows.

<details>
<summary>Sem WSL, no Git Bash</summary>

O `bot.sh` roda, mas o Windows não tem systemd — a única parte que ele não sobe sozinho é o
Postgres. O instalador da EDB já registra o serviço em auto-start; se estiver parado, rode
`net start postgresql-x64-18` num prompt de administrador. Garanta também que
`C:\Program Files\PostgreSQL\<versão>\bin` está no `PATH` (é de lá que vêm `pg_isready` e
`psql`). O resto — JDK, Maven, Ollama, Docker Desktop — funciona direto pelo `PATH`.
</details>

## 2. Banco e usuário

```bash
createdb hsrbot
psql -d hsrbot -c "CREATE USER hsrbot WITH PASSWORD 'hsrbot'; GRANT ALL ON DATABASE hsrbot TO hsrbot;"
```
> No Linux o seu usuário do SO não é superuser do Postgres por padrão — prefixe os dois
> comandos com `sudo -u postgres`.

As migrations (Flyway) rodam sozinhas no primeiro boot.

## 3. pgvector (o pulo do gato)

A extensão é do **servidor de banco**, não vai no git. E ela **não é "trusted"**, então o
usuário da app (`hsrbot`) não consegue criá-la — precisa de superuser uma vez.

Todo mundo tem pacote hoje — não precisa mais buildar da fonte:
`brew install pgvector` (macOS), `apt install postgresql-<versão>-pgvector` (Debian/Ubuntu,
casando com a versão do servidor), `pacman -S pgvector` (Arch), `dnf install pgvector` (Fedora).

Depois, cria a extensão como **superuser** (no Linux/WSL: `sudo -u postgres psql ...`):
```bash
psql -d hsrbot -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

## 4. Modelos do Ollama

```bash
ollama pull nomic-embed-text                # embeddings (768-dim)
ollama pull qwen2.5:14b-instruct-q4_K_M     # brain/chat (precisa ser tool-capable)
ollama pull qwen2.5:7b-instruct-q4_K_M      # voice (persona) — default de VOICE_MODEL_NAME
```
> Visão é opcional: `ollama pull qwen2.5vl` e preencha `VISION_MODEL_NAME` no `.env`.
> Vazio = imagens ignoradas, sem erro.

## 5. `.env`

```bash
cp .env.example .env
# edite: BOT_TOKEN, DB_*, VOICE/BRAIN/VISION_MODEL_NAME = seus modelos de chat
```

> Os 3 slots de modelo no `.env` são só o valor **inicial**. Depois disso troque com
> `./bot.sh --model` (menu com o que está instalado no Ollama): vale na hora, com o bot no ar.
> A dona do servidor também pode desligar toda a IA pelo Discord com `/ia estado:desligar`.

## 6. Construir a base de conhecimento (uma vez)

Os dados vêm do nanoka.cc (JSON estruturado, baixado na hora) e os **embeddings ficam no
Postgres**, então toda DB nova — ou cada novo patch — precisa rodar o reindex uma vez:

```bash
./bot.sh reindex     # builda o jar, sobe Postgres/Ollama e roda o reindex até o fim
```
> A versão do patch é descoberta automaticamente na home do nanoka; fixe com
> `HSR_NANOKA_VERSION=x.y.z` no `.env` se quiser travar um patch.

## 7. Web search (opcional — SearXNG)

O `./bot.sh start` já sobe o SearXNG sozinho quando `SEARXNG_URL` está no `.env`
(default `http://localhost:8888`). Só garanta o Docker de pé:

- **macOS:** o script sobe o colima automaticamente.
- **Linux:** docker nativo — `sudo systemctl enable --now docker` (e seu usuário no grupo `docker`).

Sem `SEARXNG_URL`, o `searchWeb` fica desligado e o bot responde só com a base local (sem erro).
> Porta 8888 e não 8080 porque o Payara/GlassFish do NetBeans costuma ocupar a 8080.

## 8. Rodar

```bash
./bot.sh start      # sobe infra + bot em background (logs em logs/bot.log)
./bot.sh status     # o que está no ar
./bot.sh logs       # tail -f
./bot.sh restart    # recompila e reinicia só o bot (após mudar código)
./bot.sh stop       # para o bot; `stop --all` derruba o SearXNG também
```

<details>
<summary>Alternativa manual (sem bot.sh, roda em foreground)</summary>

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; no Linux aponte pro JDK 21
set -a; source .env; set +a
mvn spring-boot:run                                # reindex: prefixe com HSR_REINDEX=true
```
</details>

## 9. Deploy num servidor 24/7 (bot no VPS, Ollama no Mac)

Os passos 1–8 montam UMA máquina com tudo junto. Esta seção é o outro cenário: o bot no ar 24
horas num servidor, com a IA continuando no Mac — ou seja, **moderação, comandos de barra e cards
funcionam sempre; papo e base de conhecimento só quando o Mac está ligado.**

| Onde | O quê |
|---|---|
| **VPS (24/7)** | bot (JVM), Postgres + pgvector, SearXNG |
| **Mac (quando ligado)** | Ollama — modelo de chat **e** de embeddings |

> **Por que o embedding não fica no servidor:** o Spring AI expõe UM `spring.ai.ollama.base-url`
> para chat e embedding, e não existe bean de `OllamaApi` customizado no projeto. Separar os dois
> daria trabalho e não resolveria nada: o `vector_store` é tabela do Postgres (fica no VPS de
> qualquer jeito) e o embedder só é chamado no caminho de conhecimento — que já precisa do modelo
> de chat. O único preço é o `HSR_AUTO_REINDEX`: se sair patch novo com o Mac desligado, ele
> reindexa no primeiro dia em que o Mac estiver de pé.

Um bot de Discord abre uma conexão **de saída** pro gateway. Não precisa de porta aberta, IP fixo,
domínio nem certificado em lugar nenhum — nem no VPS, nem no Mac.

### 9.1 A máquina

2 vCPU / 4 GB / 20 GB de disco sobra: o banco tem ~58 MB e o jar ~85 MB. Orçamento de RAM: JVM 1 GB,
Postgres ~150 MB, SearXNG ~200 MB.

```bash
sudo apt update && sudo apt install -y openjdk-21-jdk maven git curl docker.io docker-compose-v2
```

O Postgres tem que ser da **mesma versão maior do seu Mac (18)** — restaurar um dump do 18 num
servidor 16 (o que o Ubuntu 24.04 traz) quebra. Use o repositório oficial do PGDG:

```bash
sudo install -d /usr/share/postgresql-common/pgdg
sudo curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc --fail \
  https://www.postgresql.org/media/keys/ACCC4CF8.asc
echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
  | sudo tee /etc/apt/sources.list.d/pgdg.list
sudo apt update && sudo apt install -y postgresql-18 postgresql-18-pgvector
sudo systemctl enable --now postgresql docker
```

> **NÃO instale Ollama no servidor.** Ele não é usado aí (ver a nota no começo do passo 9), e um modelo de chat
> em CPU de VPS responde em minutos, não em segundos. As fontes dos cards vão dentro do jar, então
> também não precisa instalar fonte nenhuma.

### 9.2 Túnel até o Mac (Tailscale)

Nos **dois** lados:

```bash
curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up
```

Pegue o IP do Mac na rede (`tailscale ip -4` → algo como `100.x.y.z`). Por padrão o Ollama escuta só
em `127.0.0.1`, então o servidor não alcança — é preciso mudar o bind **no Mac**:

| Bind | Prós / contras |
|---|---|
| `100.x.y.z:11434` (IP do Tailscale) | **Recomendado.** Só quem está na sua rede Tailscale alcança. Em troca, `localhost:11434` deixa de responder — o `.env` do próprio Mac também passa a apontar pro IP do Tailscale. |
| `0.0.0.0:11434` | Mantém o `localhost` funcionando, mas abre o Ollama em **toda** interface — inclusive o wi-fi da facul. Só com o firewall do macOS ligado. |

```bash
launchctl setenv OLLAMA_HOST "100.x.y.z:11434"   # troque pelo IP real
brew services restart ollama
```

Teste **do servidor** antes de seguir — se isto não responder, nada do resto adianta:

```bash
curl -s http://100.x.y.z:11434/api/tags | head -c 200
```

### 9.3 Levar o banco

O dump certo é o que o `./bot.sh backup` já gera. **No Mac:**

```bash
./bot.sh backup
scp backups/hsrbot-*.sql.gz voce@servidor:~/
```

**No servidor** — a extensão `vector` precisa existir **antes** de restaurar, e é criada por
superusuário (ela não é "trusted"):

```bash
sudo -u postgres psql -c "CREATE USER hsrbot WITH PASSWORD 'a-mesma-senha-do-.env';"
sudo -u postgres createdb -O hsrbot hsrbot       # -O: hsrbot DONO do banco, senão não cria tabela
sudo -u postgres psql -d hsrbot -c "CREATE EXTENSION IF NOT EXISTS vector;"
gunzip -c ~/hsrbot-*.sql.gz | PGPASSWORD='...' psql -h localhost -U hsrbot -d hsrbot
```

> **Restaure o dump em vez de rodar o passo 6.** O dump traz o `vector_store` pronto e, principalmente,
> o `guia_arte` — arte que membros subiram, a única tabela que não regenera. Um `./bot.sh reindex` no
> servidor funcionaria, mas exige o Mac ligado e leva minutos embedando tudo através do túnel.
> O Flyway enxerga o `flyway_schema_history` restaurado e não roda migração nenhuma de novo.

### 9.4 `.env` do servidor

Copie o `.env` do Mac e mude só o que é de máquina:

```bash
OLLAMA_BASE_URL=http://100.x.y.z:11434    # o Mac, via Tailscale
SEARXNG_URL=http://localhost:8888         # SearXNG roda no próprio servidor
```

> `BACKUP_DIR` (opcional) manda o `./bot.sh backup` gravar noutro lugar — ver 9.6.

### 9.5 Subir e manter no ar

```bash
git clone <seu-repo> ~/hsr-bot && cd ~/hsr-bot
./bot.sh start
```

O `bot.sh` detecta a infra por capacidade (`brew` → `pg_ctl` → `systemctl`), então roda no Linux sem
adaptação. Mas ele não volta sozinho depois de um reboot. O mínimo é uma linha de cron:

```cron
@reboot cd /home/SEU_USUARIO/hsr-bot && ./bot.sh start >/dev/null 2>&1
```

<details>
<summary>Unidade systemd (reinicia também quando o bot morre, não só no boot)</summary>

```ini
# /etc/systemd/system/hsr-bot.service
[Unit]
Description=HsrBot
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
Type=forking
User=SEU_USUARIO
WorkingDirectory=/home/SEU_USUARIO/hsr-bot
PIDFile=/home/SEU_USUARIO/hsr-bot/.bot.pid
ExecStart=/home/SEU_USUARIO/hsr-bot/bot.sh start
ExecStop=/home/SEU_USUARIO/hsr-bot/bot.sh stop
Restart=on-failure
RestartSec=30

[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl daemon-reload && sudo systemctl enable --now hsr-bot
```
> Escrita a partir do `.bot.pid` que o `bot.sh` já mantém, mas **não testada** — não dá pra validar
> systemd de dentro do macOS. Confira com `systemctl status hsr-bot` depois de um `reboot` de teste
> antes de confiar nela; se o `Type=forking` não casar com o PID, o cron `@reboot` acima resolve.
</details>

### 9.6 Os dois crons

```cron
*  *  * * *  cd /home/SEU_USUARIO/hsr-bot && ./bot.sh heartbeat >/dev/null 2>&1
17 4  * * *  cd /home/SEU_USUARIO/hsr-bot && ./bot.sh backup    >/dev/null 2>&1
```

**heartbeat** — sem ele, com o Mac dormindo, cada menção fica pendurada no connect-timeout e as duas
vagas de `max-concurrent-inferences` travam: todo mundo passa a ouvir "estou ocupada". O heartbeat
vira a chave `ai` do `.bot.runtime` (a mesma do `/ia`) e a resposta passa a ser um "IA desligada"
limpo, em ~5s. Ele **nunca religa** uma IA que você desligou pelo `/ia` — só desfaz o que ele mesmo
fez (marca em `.bot.ia-caiu`).

**backup** — `pg_dump | gzip`, guarda os 14 últimos. Na mesma passada ele **rotaciona o
`logs/bot.log`** quando passa de 20 MB (vai pra `bot.log.1` e o arquivo é zerado com o bot no ar):
o log divide disco com o Postgres e com estes backups, e disco cheio derruba os três de uma vez.
Grava **na própria VPS**, o que não protege contra perder a VPS. Mande pra fora:

```cron
30 4 * * *  rclone copy /home/SEU_USUARIO/hsr-bot/backups remoto:hsrbot-backups
```

### 9.7 O dia a dia: subir código, baixar dado

Os dois comandos rodam **no Mac** e se guiam por `DEPLOY_HOST`/`DEPLOY_DIR` no `.env` daqui —
chaves que o servidor não tem, e é isso que impede o `pull-db` de rodar do lado errado.

```bash
./bot.sh deploy    # commit atual → git → servidor: backup, pull, rebuild, restart, status
./bot.sh pull-db   # produção → Mac: DROPA o banco local e põe a cópia no lugar
```

O `deploy` recusa árvore suja (só commit viaja) e o `restart` do outro lado compila **antes** de
derrubar: build quebrado deixa a versão anterior no ar em vez de deixar buraco.

**O fluxo do banco é de mão única, servidor → Mac.** O servidor é a produção: `guia_arte`, o placar
do `/rank` e as tier lists só existem lá. Restaurar o dump do Mac no servidor apagaria os três, e
com eles o `flyway_schema_history` — é a única perda irreversível deste projeto. Esquema não anda
por dump: migração de Flyway viaja no git, com o código.

`vector_store` e as tabelas do nanoka não precisam de cópia nenhuma: com `HSR_AUTO_REINDEX` ligado
(padrão) o servidor se reindexa sozinho no primeiro dia em que o Mac estiver de pé.

### 9.8 Conferir que ficou de pé

| Checagem | Comando |
|---|---|
| Bot conectou | `./bot.sh status` |
| Cards renderizam (fonte no jar, headless) | `/build` ou `/guia` no Discord |
| Ollama alcançável | `./bot.sh heartbeat` — silêncio = tudo certo |
| Queda da máquina de IA degrada limpo | desligue-a, espere 1 min, mencione o bot |
| Backup restaura | `gunzip -t backups/*.sql.gz` |

---

### Resumo do que NÃO vai no git
- `.env` (token) → recriar do `.env.example`
- extensão pgvector + `CREATE EXTENSION` → passo 3, em cada DB nova
- conteúdo de `vector_store` (embeddings) → passo 6, em cada DB nova
- modelos do Ollama → passo 4
- container SearXNG (e colima no mac) → passo 7
- `backups/` e `.bot.ia-caiu` → estado de máquina, gerados pelo `bot.sh` (passo 9.6)

> **As fontes dos cards, ao contrário, VÃO no git** (`src/main/resources/fonts/`). Elas eram
> "ambiente" — o renderer procurava DIN na lista de fontes instaladas — e num Linux isso caía
> silenciosamente numa sans genérica, mudando todos os cards sem erro nenhum. Agora saem de dentro
> do jar, e o render é idêntico em qualquer máquina.
