#!/usr/bin/env bash
#
# bot.sh — sobe toda a infra do HsrBot e gerencia o ciclo de vida do processo.
#
#   ./bot.sh start      # sobe infra (Postgres, Ollama, colima+SearXNG) e inicia o bot
#   ./bot.sh restart    # recompila e reinicia SÓ o bot (infra fica de pé) — use após mudar código
#   ./bot.sh stop       # para o bot.  `stop --all` também derruba SearXNG/colima
#   ./bot.sh status     # mostra o que está no ar
#   ./bot.sh activity playing "Honkai: Star Rail!"  # muda a atividade do bot SEM reiniciar
#       tipos: playing|watching|listening|competing|custom | streaming <url> <texto>
#       sem tipo => playing (ex.: activity "Honkai: Star Rail!");  activity (sem nada) => limpa
#   ./bot.sh --model    # menu interativo pra trocar os modelos do Ollama SEM reiniciar
#       lista o que está instalado (ollama list) e pergunta voz → cérebro → visão
#       (visão pode ficar em branco = sem suporte a imagens)
#   ./bot.sh logs       # tail -f do log do bot
#   ./bot.sh reindex    # (re)constrói a base de conhecimento HSR e sai
#   ./bot.sh deploy     # (no Mac) empurra o commit atual e reinicia o bot no servidor
#   ./bot.sh pull-db    # (no Mac) DROPA o banco local e põe a cópia da produção no lugar
#
# O bot roda em background; PID em .bot.pid, logs em logs/bot.log.
#
# Plataformas: macOS (brew), Linux (Arch/Ubuntu/Debian/Fedora — systemd) e Windows via WSL2.
# A infra é detectada por capacidade (brew → pg_ctl → systemctl), não por distro. No Windows,
# use WSL2: lá dentro é Linux e tudo aqui vale. Git Bash roda o script, mas o Postgres tem
# que estar de pé por conta própria (o instalador já registra o serviço em auto-start).
#
# Config de runtime (modelos + chave geral da IA) vive em .bot.runtime, um key=value que o
# bot relê a cada ~5s — mesmo mecanismo do .bot.activity. Ele MANDA sobre o .env. O comando
# /ia do Discord escreve a linha `ai=` nesse mesmo arquivo.

set -uo pipefail

# ---- localização / paths ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
export PATH="/opt/homebrew/bin:$PATH"

# Flags da JVM, sobrescrevíveis pelo ambiente (o servidor pode querer outro -Xmx que o Mac).
#   -Xmx        o default da JVM é 1/4 da RAM. Num VPS de 2 GB isso dá 512 MB e os renderers
#               de card (BufferedImage de 1280px + arte em full-res) estouram com OOM.
#   headless    servidor não tem display; sem isso o Java2D falha ao inicializar.
#   UTF-8       nomes de personagem carregam "•" e o texto é todo PT-BR acentuado. Numa box
#               Linux recém-criada o locale costuma ser POSIX, e aí sun.jnu.encoding vira
#               ASCII: nome de arquivo e argv chegam corrompidos no bot.
JAVA_OPTS="${JAVA_OPTS:--Xmx1g -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8}"

# Descobre a formula do postgres do brew (postgresql@18, @17, ...) em vez de hardcodar uma
# versão: quando migramos @14 → @18 (2026-07-31) TODO o caminho de Postgres virou no-op
# silencioso e o bot subia sem banco. Vazia no Linux/sem brew — aí os branches de brew são
# pulados e valem os de pg_ctl/systemctl. Postgres versionado é keg-only: sem esse bin no
# PATH não existe nem pg_isready nem psql.
BREW_PREFIX="$(brew --prefix 2>/dev/null)"
# Sem brew, BREW_PREFIX é vazio e o glob abaixo viraria /opt/postgresql@* — um caminho
# real no Linux. O teste evita achar uma "formula" que o brew não sabe subir.
PG_FORMULA=""
[ -n "$BREW_PREFIX" ] && PG_FORMULA="$(ls -d "$BREW_PREFIX"/opt/postgresql@* 2>/dev/null | sort -V | tail -1)"
PG_FORMULA="${PG_FORMULA##*/}"
[ -n "$PG_FORMULA" ] && export PATH="$BREW_PREFIX/opt/$PG_FORMULA/bin:$PATH"

PID_FILE="$SCRIPT_DIR/.bot.pid"
RUNTIME_FILE="$SCRIPT_DIR/.bot.runtime"
LOG_DIR="$SCRIPT_DIR/logs"
LOG_FILE="$LOG_DIR/bot.log"
JAR="$SCRIPT_DIR/target/hsr-bot-2.0.0.jar"
SEARXNG_DIR="$SCRIPT_DIR/docker/searxng"
mkdir -p "$LOG_DIR"

# ---- cores ----
if [ -t 1 ]; then C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_DIM=$'\033[2m'; C_B=$'\033[1m'; C_0=$'\033[0m'
else C_OK=; C_WARN=; C_ERR=; C_DIM=; C_B=; C_0=; fi
ok()   { echo "${C_OK}✓${C_0} $*"; }
warn() { echo "${C_WARN}!${C_0} $*"; }
err()  { echo "${C_ERR}✗${C_0} $*" >&2; }
step() { echo "${C_B}▶ $*${C_0}"; }

# ---- .bot.runtime (modelos + chave da IA; ver cabeçalho) ----
# Lê uma chave do arquivo. Vazio quando o arquivo ou a chave não existem.
runtime_get() { [ -f "$RUNTIME_FILE" ] && sed -n "s/^$1=//p" "$RUNTIME_FILE" | tail -1; return 0; }
runtime_has() { [ -f "$RUNTIME_FILE" ] && grep -q "^$1=" "$RUNTIME_FILE"; }

# ---- pré-requisitos ----
require_env() {
  [ -f "$SCRIPT_DIR/.env" ] || { err ".env não encontrado. Copie de .env.example."; exit 1; }
  set -a; # shellcheck disable=SC1091
  source "$SCRIPT_DIR/.env"; set +a
  : "${DB_USER:=hsrbot}" "${DB_PASSWORD:=hsrbot}"
  : "${EMBED_MODEL_NAME:=nomic-embed-text}"
  : "${VOICE_MODEL_NAME:=gemma4:12b-it-q8_0}" "${BRAIN_MODEL_NAME:=$VOICE_MODEL_NAME}"
  : "${VISION_MODEL_NAME:=}"
  : "${OLLAMA_BASE_URL:=http://localhost:11434}"
  : "${BOT_ROLE:=bot}"   # "ollama" = host que só serve modelos; ver assert_papel_bot

  # .bot.runtime manda sobre o .env — é o que o bot está de fato usando, então status e
  # checagem de modelos precisam olhar pra ele. `vision` é o único slot onde VAZIO tem
  # significado (= visão desligada), por isso testa a presença da chave, não o valor.
  local v
  v="$(runtime_get voice)"; [ -n "$v" ] && VOICE_MODEL_NAME="$v"
  v="$(runtime_get brain)"; [ -n "$v" ] && BRAIN_MODEL_NAME="$v"
  runtime_has vision && VISION_MODEL_NAME="$(runtime_get vision)"
  return 0
}

# Aceita qualquer JDK >= 21 (bytecode alvo é 21; ver <java.version> no pom.xml)
java_ge_21() { [ "$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')" -ge 21 ] 2>/dev/null; }

resolve_java() {
  local jh
  if jh="$(/usr/libexec/java_home -v 21+ 2>/dev/null)"; then         # macOS: 21 ou mais novo
    export JAVA_HOME="$jh"
  elif [ -n "${JAVA_HOME:-}" ] && java_ge_21 "$JAVA_HOME/bin/java"; then
    export JAVA_HOME                                                 # JAVA_HOME já serve
  elif command -v java >/dev/null 2>&1 && java_ge_21 java; then
    # java >= 21 no PATH — deriva JAVA_HOME dele (sobrescreve um JAVA_HOME antigo que o mvn usaria)
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  else
    err "JDK 21+ não encontrado."
    echo "  macOS:  brew install --cask corretto@21" >&2
    echo "  Ubuntu: apt install openjdk-21-jdk       Arch: pacman -S jdk21-openjdk" >&2
    echo "  Fedora: dnf install java-21-openjdk-devel" >&2
    echo "  Windows: instale o Temurin/Corretto 21 e aponte JAVA_HOME pra ele" >&2
    exit 1
  fi
}

wait_for() { # wait_for <desc> <cmd...>  — tenta por ~30s
  local desc="$1"; shift
  for _ in $(seq 1 30); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  warn "timeout esperando: $desc"; return 1
}

# ---- infra ----
# Data dir do Postgres (onde vive o postmaster.pid) — portável mac/linux.
# Ordem: $PGDATA explícito > homebrew > caminhos comuns de distro Linux.
pg_data_dir() {
  [ -n "${PGDATA:-}" ] && { echo "$PGDATA"; return; }
  if [ -n "$PG_FORMULA" ]; then
    local d="$BREW_PREFIX/var/$PG_FORMULA"; [ -d "$d" ] && { echo "$d"; return; }
  fi
  # Debian/Ubuntu: /var/lib/postgresql/<versão>/main  |  Arch: /var/lib/postgres/data
  # Fedora/RHEL: /var/lib/pgsql[/<versão>]/data       |  imagem/manual: .../postgresql/data
  local d
  for d in /var/lib/postgresql/*/main /var/lib/postgres/data /var/lib/postgresql/data \
           /var/lib/pgsql/data /var/lib/pgsql/*/data /usr/local/var/postgresql@*; do
    [ -f "$d/PG_VERSION" ] && { echo "$d"; return; }
  done
}

# Postgres que morre sem limpar deixa um postmaster.pid órfão; o SO pode ter reciclado
# aquele PID pra outro processo → o postgres se recusa a subir ("lock file already exists").
# Remove o pid se ele não aponta mais pra um postgres vivo.
# ponytail: só cobre postgres local (dev/homebrew) — num postgres de sistema o data dir é
# de outro dono e o systemd já limpa sozinho.
clear_stale_pid() {
  local dir pidfile pid
  dir="$(pg_data_dir)"; [ -n "$dir" ] || return 0
  pidfile="$dir/postmaster.pid"; [ -f "$pidfile" ] || return 0
  # Cluster do sistema (Arch/Ubuntu/Fedora): o data dir é do usuário `postgres`, o rm daria
  # "permission denied" depois de avisar que ia limpar — e o systemd já limpa sozinho.
  [ -O "$pidfile" ] || return 0
  pid="$(head -1 "$pidfile" 2>/dev/null)"
  if kill -0 "$pid" 2>/dev/null && ps -p "$pid" -o comm= 2>/dev/null | grep -qiE 'postgres|postmaster'; then
    return 0   # PID é um postgres vivo de verdade — não mexe
  fi
  warn "postmaster.pid órfão em $dir (PID $pid não é postgres) — removendo"
  rm -f "$pidfile"
}

# Retorna != 0 se o Postgres não estiver aceitando conexão no fim — quem chama DEVE abortar:
# sem banco o bot morre no Flyway com 60 linhas de stack trace em vez de uma mensagem clara.
start_postgres() {
  command -v pg_isready >/dev/null 2>&1 || {
    err "pg_isready não encontrado no PATH — instale o client do Postgres."
    echo "  macOS: brew install postgresql@18      Debian/Ubuntu: apt install postgresql-client" >&2
    echo "  Arch:  pacman -S postgresql            Fedora: dnf install postgresql" >&2
    echo "  Windows (Git Bash/WSL): ponha .../PostgreSQL/<versão>/bin no PATH" >&2
    return 1
  }
  if pg_isready -h localhost -q 2>/dev/null; then ok "Postgres já no ar"; return 0; fi
  clear_stale_pid
  step "Subindo Postgres"
  local dir; dir="$(pg_data_dir)"
  if [ -n "$PG_FORMULA" ]; then
    brew services start "$PG_FORMULA" >/dev/null 2>&1
  elif command -v pg_ctl >/dev/null 2>&1 && [ -n "$dir" ] && [ -O "$dir" ]; then
    # Só quando o data dir é NOSSO (initdb manual/dev). Num cluster de distro ele é do
    # usuário `postgres`: pg_ctl como você devolve "permission denied" na hora e o
    # wait_for gastava 30s pra concluir o óbvio — por isso o systemctl vem logo abaixo.
    pg_ctl -D "$dir" -l "$LOG_DIR/postgres.log" start >/dev/null 2>&1
  elif command -v systemctl >/dev/null 2>&1; then
    # Arch, Ubuntu/Debian e Fedora chamam a unit de `postgresql`. Sem redirecionar o sudo:
    # ele precisa mostrar o prompt de senha, senão parece que travou.
    systemctl start postgresql >/dev/null 2>&1 || sudo systemctl start postgresql
  else
    err "não sei subir o Postgres neste ambiente — inicie manualmente e rode de novo"
    echo "  Windows: o instalador registra um serviço; num prompt de administrador rode" >&2
    echo "           net start postgresql-x64-<versão>   (ex.: postgresql-x64-18)" >&2
    return 1
  fi
  local pglog="$LOG_DIR/postgres.log"
  [ -n "$PG_FORMULA" ] && pglog="$BREW_PREFIX/var/log/$PG_FORMULA.log"
  wait_for "Postgres" pg_isready -h localhost -q || { err "Postgres não subiu — veja $pglog"; return 1; }
  ok "Postgres no ar"
}

start_ollama() {
  if curl -sf "$OLLAMA_BASE_URL/api/tags" >/dev/null 2>&1; then ok "Ollama já no ar"
  elif ! command -v ollama >/dev/null 2>&1; then
    # Sem esse teste o `nohup ollama serve` falhava com "command not found" e o wait_for
    # ainda gastava 30s antes de reclamar.
    warn "ollama não encontrado no PATH — IA desligada (https://ollama.com/download)"; return 0
  else
    step "Subindo Ollama"
    # Arch e Ubuntu instalam o ollama como serviço do systemd, com os modelos em
    # /var/lib/ollama. Um `ollama serve` seu subiria um 2º daemon lendo ~/.ollama (vazio),
    # e TODO modelo apareceria como "não encontrado".
    if command -v systemctl >/dev/null 2>&1 && systemctl cat ollama.service >/dev/null 2>&1; then
      systemctl start ollama >/dev/null 2>&1 || sudo systemctl start ollama
    else
      nohup ollama serve >"$LOG_DIR/ollama.log" 2>&1 &
    fi
    wait_for "Ollama" curl -sf "$OLLAMA_BASE_URL/api/tags" && ok "Ollama no ar"
  fi
  # checagem de modelos (não baixa sozinho — só avisa). VISION vazio = visão desligada.
  local tags; tags="$(curl -sf "$OLLAMA_BASE_URL/api/tags" 2>/dev/null)"
  for m in "$EMBED_MODEL_NAME" "$VOICE_MODEL_NAME" "$BRAIN_MODEL_NAME" "$VISION_MODEL_NAME"; do
    [ -n "$m" ] || continue
    echo "$tags" | grep -q "\"${m%%:*}" || warn "modelo '$m' não encontrado — rode: ollama pull $m"
  done
}

# compose v2 é plugin (`docker compose`); o binário standalone `docker-compose` só existe
# em instalações antigas/brew. Usa o que houver.
compose() {
  if docker compose version >/dev/null 2>&1; then docker compose "$@"; else docker-compose "$@"; fi
}

start_searxng() {
  [ -n "${SEARXNG_URL:-}" ] || { warn "SEARXNG_URL vazio — web search desligado (lookupHsr ainda funciona)"; return 0; }
  # Linux roda docker nativo; colima é só a VM do docker no macOS.
  if ! docker info >/dev/null 2>&1; then
    if command -v colima >/dev/null 2>&1; then
      if ! colima status 2>&1 | grep -qi running; then step "Subindo colima"; colima start >/dev/null 2>&1; fi
    else warn "docker indisponível (daemon parado e sem colima) — web search desligado"; return 0; fi
  fi
  if curl -sf "$SEARXNG_URL/" >/dev/null 2>&1; then ok "SearXNG já no ar ($SEARXNG_URL)"
  else step "Subindo SearXNG"; (cd "$SEARXNG_DIR" && compose up -d >/dev/null 2>&1)
       wait_for "SearXNG" curl -sf "$SEARXNG_URL/" && ok "SearXNG no ar ($SEARXNG_URL)"; fi
}

# ---- ciclo de vida do bot ----
bot_running() { [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

# Detecta QUALQUER instância do bot que não seja a nossa (.bot.pid) — tipicamente um run
# do IntelliJ esquecido. Duas instâncias no mesmo BOT_TOKEN = o Discord entrega a mensagem
# pra uma delas aleatoriamente; se a outra for código antigo, você vê respostas erradas
# de forma intermitente (exatamente o bug do RAG "que parou de funcionar").
# `jps -l` (vem em todo JDK, que este script já exige) imprime "<pid> <jar-ou-main-class>"
# igual em macOS, Linux e Windows — o pgrep de antes não existe no Git Bash e ainda podia
# casar com o próprio grep. Vê só JVMs do seu usuário, que é justamente o caso (IntelliJ).
foreign_instances() {
  local self jps; self="$(cat "$PID_FILE" 2>/dev/null || echo -1)"
  jps="${JAVA_HOME:+$JAVA_HOME/bin/}jps"
  command -v "$jps" >/dev/null 2>&1 || jps=jps            # JAVA_HOME sem jps → tenta o PATH
  command -v "$jps" >/dev/null 2>&1 || return 0           # sem JDK visível: sem detecção
  # `--` impede o grep de tratar o sentinela "-1" (sem .bot.pid) como flag.
  "$jps" -l 2>/dev/null | awk '/hsr-bot-2\.0\.0\.jar|HsrBotApplicationKt/ {print $1}' \
    | sort -u | grep -vx -- "$self"
}

# O bot roda no servidor; o Mac ficou só com o Ollama. Duas instâncias no MESMO token não dão
# erro nenhum — as duas conectam, as duas respondem, e o usuário vê resposta dobrada e memória
# saindo pela metade. O assert_single_instance abaixo só enxerga processos DESTA máquina, então
# essa é a única barreira possível contra o `./bot.sh start` de costume no Mac.
assert_papel_bot() {
  [ "${BOT_ROLE:-bot}" = "ollama" ] || return 0
  err "BOT_ROLE=ollama neste host — o bot não sobe aqui."
  echo "  Esta máquina serve só o Ollama; quem roda o bot é o servidor." >&2
  echo "  Duas instâncias no mesmo token = resposta duplicada, e nenhuma das duas avisa." >&2
  echo "  Se este host virou o do bot mesmo, tire BOT_ROLE do .env." >&2
  return 1
}

assert_single_instance() {
  local others; others="$(foreign_instances)"
  [ -z "$others" ] && return 0
  err "Já existe outra instância do bot rodando (PID: $(echo "$others" | tr '\n' ' '))."
  echo "  Provavelmente um run do IntelliJ. Pare-o (botão ⏹) ou mate: kill $(echo "$others" | tr '\n' ' ')"
  echo "  Duas instâncias no mesmo token causam respostas erradas intermitentes."
  return 1
}

# Guarda a execução anterior em bot.log.1 e zera bot.log. Com um argumento, só roda se o
# arquivo passar de N bytes — é assim que o cron do backup chama.
#
# copy-and-truncate (e não `mv`): a JVM está com o arquivo aberto. Renomear leva o fd junto e
# o bot passa a escrever no .1, que ninguém mais rotaciona — o disco enche do mesmo jeito, só
# que com outro nome. Truncar preserva o inode, e o `>>` do start_bot faz a escrita seguinte
# começar do zero.
rotate_log() {
  local limite="${1:-0}"
  [ -s "$LOG_FILE" ] || return 0
  if [ "$limite" -gt 0 ]; then
    local tam; tam="$(wc -c <"$LOG_FILE")"
    [ "$tam" -gt "$limite" ] || return 0
  fi
  cp "$LOG_FILE" "$LOG_FILE.1" 2>/dev/null && : >"$LOG_FILE"
}

build() {
  step "Compilando (mvn package)"
  if mvn -q clean package -DskipTests; then ok "Build OK"; else err "Build falhou — bot NÃO reiniciado"; return 1; fi
}

start_bot() {
  if bot_running; then warn "Bot já rodando (PID $(cat "$PID_FILE")). Use restart."; return 0; fi
  assert_single_instance || return 1
  assert_papel_bot || return 1
  [ -f "$JAR" ] || build || return 1
  # Sempre no boot: o wait_for abaixo procura "Started HsrBot" no arquivo, e com append um
  # "Started" da execução ANTERIOR faria o script cantar vitória de um bot que nem subiu.
  rotate_log
  step "Iniciando bot"
  # `>>` e não `>`: com O_APPEND o rotate_log abaixo pode truncar o arquivo COM o bot no ar e
  # a JVM continua escrevendo do zero. Com `>` a escrita usa offset próprio, e truncar por
  # fora deixa um arquivo esparso que volta ao mesmo tamanho na primeira linha. O truncate no
  # lugar do `>` de antes vem do rotate_log, que roda no cron junto do backup.
  nohup java $JAVA_OPTS -jar "$JAR" >>"$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  if wait_for "bot" grep -q "Started HsrBot" "$LOG_FILE"; then
    ok "Bot no ar (PID $(cat "$PID_FILE")) — logs: ./bot.sh logs"
  else
    err "Bot não confirmou startup; veja ./bot.sh logs"; tail -15 "$LOG_FILE"; return 1
  fi
}

stop_bot() {
  if bot_running; then
    local pid; pid="$(cat "$PID_FILE")"
    step "Parando bot (PID $pid)"; kill "$pid" 2>/dev/null
    for _ in $(seq 1 10); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
    ok "Bot parado"
  else warn "Bot não estava rodando"; fi
  rm -f "$PID_FILE"
}

# ---- comandos ----
cmd_start() {
  require_env; resolve_java
  start_postgres || return 1   # sem banco não adianta subir o bot — ele morre no Flyway
  start_ollama; start_searxng
  start_bot
}

cmd_restart() { # recompila e reinicia só o bot — infra intacta
  require_env; resolve_java
  step "Restart: recompilando e reiniciando o bot"
  build || return 1
  stop_bot
  start_bot
}

cmd_stop() {
  stop_bot
  if [ "${1:-}" = "--all" ]; then
    require_env
    step "Derrubando SearXNG"; (cd "$SEARXNG_DIR" && compose down >/dev/null 2>&1) && ok "SearXNG parado"
    warn "colima/docker e Postgres deixados de pé (compartilhados). Pare manualmente se quiser:"
    if [ -n "$PG_FORMULA" ]; then echo "    colima stop   |   brew services stop $PG_FORMULA"
    else echo "    sudo systemctl stop postgresql ollama"; fi
  fi
}

cmd_status() {
  require_env
  echo "${C_B}Infra${C_0}"
  pg_isready -h localhost -q 2>/dev/null && ok "Postgres" || err "Postgres"
  curl -sf "$OLLAMA_BASE_URL/api/tags" >/dev/null 2>&1 && ok "Ollama" || err "Ollama"
  if [ -n "${SEARXNG_URL:-}" ]; then curl -sf "$SEARXNG_URL/" >/dev/null 2>&1 && ok "SearXNG ($SEARXNG_URL)" || err "SearXNG ($SEARXNG_URL)"
  else warn "SearXNG desligado (SEARXNG_URL vazio)"; fi
  local docs; docs="$(PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USER" -d hsrbot -tAc 'select count(*) from vector_store' 2>/dev/null)"
  [ -n "$docs" ] && echo "  ${C_DIM}vector_store: $docs docs${C_0}"
  echo "${C_B}Modelos${C_0} ${C_DIM}(./bot.sh --model troca sem restart)${C_0}"
  echo "  voz=$VOICE_MODEL_NAME  cérebro=$BRAIN_MODEL_NAME  visão=${VISION_MODEL_NAME:-desligada}"
  [ "$(runtime_get ai)" = "off" ] && warn "IA DESLIGADA pelo /ia — só comandos sem IA funcionam"
  echo "${C_B}Bot${C_0} ${C_DIM}(papel deste host: $BOT_ROLE)${C_0}"
  bot_running && ok "Bot rodando (PID $(cat "$PID_FILE"))" || warn "Bot parado (via script)"
  local others; others="$(foreign_instances)"
  [ -n "$others" ] && err "Instância(s) FORA do script rodando (PID: $(echo "$others" | tr '\n' ' ')) — risco de resposta duplicada/errada!"
  return 0
}

cmd_logs() { tail -f "$LOG_FILE"; }

# Muda a atividade do bot sem reiniciar: escreve `<tipo> <texto>` num arquivo que o
# ActivityStatusWatcher no bot lê a cada ~5s. Tipos: playing|watching|listening|competing|
# custom | streaming <url> <texto>. Sem tipo => playing. `activity` sem nada => limpa.
cmd_activity() {
  local text="$*"
  printf '%s' "$text" > "$SCRIPT_DIR/.bot.activity"
  if [ -n "$text" ]; then ok "Atividade: \"$text\" (aplica em ~5s)"
  else ok "Atividade limpa (aplica em ~5s)"; fi
  bot_running || warn "Bot parado — a atividade será aplicada quando ele subir."
}

# Menu interativo de troca de modelos. Escreve .bot.runtime, que o bot relê em ~5s — sem
# restart, sem mexer no .env. Com o bot parado também funciona: vale no próximo start.
# O modelo de EMBEDDING fica de fora de propósito: trocá-lo muda a dimensão dos vetores e
# exige `./bot.sh reindex`, então não é um hot swap.
NONE_LABEL="(nenhum — desliga o suporte a imagens)"

# pick_model <rótulo> <atual> [--allow-none] → resultado em $PICKED.
# Devolve por variável, não por stdout: no EOF (Ctrl-D) o `select` do bash cospe um \n
# no stdout, que virava um nome de modelo com quebra de linha dentro do .bot.runtime.
pick_model() {
  local label="$1" current="$2" allow_none="${3:-}" choice m
  local opts=()
  while IFS= read -r m; do [ -n "$m" ] && opts+=("$m"); done <<< "$OLLAMA_MODELS"
  [ "$allow_none" = "--allow-none" ] && opts+=("$NONE_LABEL")

  echo ""
  echo "${C_B}Modelo de $label${C_0} ${C_DIM}(atual: ${current:-nenhum})${C_0}"
  PS3="Número: "
  select choice in "${opts[@]}"; do
    [ -n "$choice" ] && break
    echo "  ${C_WARN}opção inválida${C_0}"
  done
  # select sai com $choice vazio no EOF: mantém o atual em vez de apagar o slot.
  [ -z "${choice:-}" ] && choice="$current"
  [ "$choice" = "$NONE_LABEL" ] && choice=""
  PICKED="$choice"
}

cmd_model() {
  require_env
  [ -t 0 ] || { err "--model é interativo: rode num terminal."; return 1; }
  command -v ollama >/dev/null 2>&1 || { err "ollama não encontrado no PATH."; return 1; }

  # `ollama list`: 1ª linha é cabeçalho, 1ª coluna é o nome:tag.
  OLLAMA_MODELS="$(ollama list 2>/dev/null | tail -n +2 | awk 'NF {print $1}' | sort)"
  [ -n "$OLLAMA_MODELS" ] || { err "nenhum modelo instalado — rode: ollama pull <modelo>"; return 1; }

  local voice brain vision
  pick_model "VOZ ${C_DIM}(respostas em persona + /contexto-do-canal)${C_0}" "$VOICE_MODEL_NAME"; voice="$PICKED"
  pick_model "CÉREBRO ${C_DIM}(portão de intenção, juiz, retelling da base)${C_0}" "$BRAIN_MODEL_NAME"; brain="$PICKED"
  pick_model "VISÃO ${C_DIM}(lê imagens anexadas)${C_0}" "$VISION_MODEL_NAME" --allow-none; vision="$PICKED"

  # Preserva a chave `ai` (escrita pelo /ia no Discord) — reescrever o arquivo inteiro sem
  # ela religaria a IA que a dona do servidor desligou.
  local ai; ai="$(runtime_get ai)"; [ -n "$ai" ] || ai="on"
  printf 'voice=%s\nbrain=%s\nvision=%s\nai=%s\n' "$voice" "$brain" "$vision" "$ai" > "$RUNTIME_FILE" || {
    err "não consegui escrever $RUNTIME_FILE"; return 1; }

  echo ""
  ok "voz:     $voice"
  ok "cérebro: $brain"
  [ -n "$vision" ] && ok "visão:   $vision" || warn "visão:   desligada (imagens serão ignoradas)"
  [ "$ai" = "on" ] || warn "a IA está DESLIGADA no momento (/ia estado:ligar no Discord pra voltar)"
  if bot_running; then ok "aplica em ~5s — sem restart"
  else warn "Bot parado — vale no próximo ./bot.sh start"; fi
}

cmd_reindex() {
  require_env; resolve_java
  start_postgres || return 1
  start_ollama
  # Sempre recompila: reindex com jar velho embeda o conjunto de docs ANTIGO e a versão
  # do nanoka não muda — o auto-reindex nunca corrige (aconteceu 2026-07-07).
  build || return 1
  local was_running=0; bot_running && was_running=1 && stop_bot
  step "Reindex da base HSR (HSR_REINDEX=true)"
  local rlog="$LOG_DIR/reindex.log"
  HSR_REINDEX=true nohup java $JAVA_OPTS -jar "$JAR" >"$rlog" 2>&1 &
  local rpid=$!
  echo "  ${C_DIM}acompanhe: tail -f $rlog${C_0}"
  # Um reindex leva MINUTOS (fetch do nanoka + embed de ~2k docs) — o wait_for genérico
  # de 30s matava o processo no meio do load. Espera dedicada: até 30 min, abortando
  # cedo se o processo morrer sozinho (crash/abort do ingester).
  local reindex_ok=1
  for _ in $(seq 1 1800); do
    grep -q "HSR reindex complete" "$rlog" 2>/dev/null && { reindex_ok=0; break; }
    kill -0 "$rpid" 2>/dev/null || break
    sleep 1
  done
  if [ "$reindex_ok" = 0 ]; then
    grep -E "profiles|relic|enemies|light cone|embedded batch .*/|Skipped|reindex complete" "$rlog" | tail -8 | sed 's/^/  /'
    ok "Reindex concluído"
  else err "Reindex não confirmou conclusão; veja $rlog"; fi
  kill "$rpid" 2>/dev/null; for _ in $(seq 1 10); do kill -0 "$rpid" 2>/dev/null || break; sleep 1; done
  kill -9 "$rpid" 2>/dev/null
  if [ "$was_running" = 1 ]; then warn "Bot estava rodando antes — reiniciando"; start_bot; fi
  # (o `[ ... ] &&` antigo fazia a função sair com código 1 quando o bot NÃO estava rodando)
}

# ---- heartbeat da IA (para o cron do servidor) ----
# Com o bot no servidor 24/7 e o Ollama no Mac, o Mac fica fora do ar boa parte do tempo.
# Mac DESLIGADO devolve "connection refused" na hora e os fallbacks entram limpo; Mac
# DORMINDO com o túnel de pé engole os pacotes, e aí cada menção paga o connect-timeout
# antes de falhar — com max-concurrent-inferences=2, duas menções presas travam as duas
# vagas e todo mundo passa a ouvir "estou ocupada".
#
# Virando a chave `ai` do .bot.runtime — a MESMA que o /ia usa — a resposta passa a ser o
# "IA desligada" que o MentionReplyListener já sabe dar, e o bot relê o arquivo em ~5s.
#
# O arquivo-marca existe para não brigar com a dona do servidor: se ELA desligou a IA pelo
# /ia, o heartbeat não pode religar quando o Mac voltar. Só religa o que ele mesmo desligou.
#
# Cron:  * * * * * cd /caminho/hsr-bot && ./bot.sh heartbeat >/dev/null 2>&1
HEARTBEAT_FLAG="$SCRIPT_DIR/.bot.ia-caiu"

# Troca UMA chave preservando as outras. Arquivo temporário + mv porque `sed -i` tem sintaxe
# diferente no BSD (macOS) e no GNU (servidor), e porque o mv é atômico — o bot lê esse
# arquivo a cada 5s e não pode pegar ele pela metade.
runtime_set() {
  local k="$1" v="$2" tmp="$RUNTIME_FILE.tmp"
  if runtime_has "$k"; then
    sed "s|^$k=.*|$k=$v|" "$RUNTIME_FILE" > "$tmp" && mv -f "$tmp" "$RUNTIME_FILE"
  else
    printf '%s=%s\n' "$k" "$v" >> "$RUNTIME_FILE"
  fi
}

cmd_heartbeat() {
  require_env
  local ai; ai="$(runtime_get ai)"; [ -n "$ai" ] || ai="on"
  if curl -sf -m 3 "$OLLAMA_BASE_URL/api/tags" >/dev/null 2>&1; then
    [ -f "$HEARTBEAT_FLAG" ] || return 0          # já ligada, ou desligada por uma pessoa
    runtime_set ai on && rm -f "$HEARTBEAT_FLAG" && ok "Ollama voltou — IA religada"
  else
    [ "$ai" = "on" ] || return 0                  # já desligada
    runtime_set ai off && : > "$HEARTBEAT_FLAG" && warn "Ollama fora do ar ($OLLAMA_BASE_URL) — IA desligada"
  fi
}

# ---- backup do Postgres ----
# `guia_arte` é a única tabela que NÃO regenera: arte que membros subiram, e o link do
# Discord expira em horas (ver V26__guia_arte.sql). vector_store e as tabelas do nanoka
# voltam com um reindex. O banco todo tem ~58 MB, então o dump é inteiro — não vale a
# complexidade de escolher tabela.
#
# ATENÇÃO: isto grava na PRÓPRIA máquina. Aponte BACKUP_DIR pra um volume externo ou
# sincronize o diretório pra fora da box — backup na mesma VPS não protege contra perder
# a VPS, que é justamente o risco novo de sair do seu Mac.
#
# Cron:  17 4 * * * cd /caminho/hsr-bot && ./bot.sh backup >/dev/null 2>&1
cmd_backup() {
  require_env
  command -v pg_dump >/dev/null 2>&1 || { err "pg_dump não encontrado (apt install postgresql-client)"; return 1; }
  local dir="${BACKUP_DIR:-$SCRIPT_DIR/backups}"
  mkdir -p "$dir" || { err "não consegui criar $dir"; return 1; }
  local out="$dir/hsrbot-$(date +%Y%m%d-%H%M%S).sql.gz"
  step "Dump do Postgres → $out"
  # Mesma passada diária, porque é o único cron que já existe: o log do bot compartilha o
  # disco com o Postgres e com estes backups, e um disco cheio derruba os três de uma vez.
  rotate_log $((20 * 1024 * 1024))
  if PGPASSWORD="$DB_PASSWORD" pg_dump -h localhost -U "$DB_USER" hsrbot | gzip > "$out"; then
    ok "Backup OK ($(du -h "$out" | cut -f1))"
    # Mantém os 14 mais recentes. `ls -t` basta: os nomes têm timestamp e o diretório é nosso.
    ls -1t "$dir"/hsrbot-*.sql.gz 2>/dev/null | tail -n +15 | while read -r old; do rm -f "$old"; done
  else
    # Sem isto sobra um .gz truncado que parece um backup válido — o pior tipo de backup.
    rm -f "$out"; err "pg_dump falhou — backup NÃO gravado"; return 1
  fi
}

# ---- deploy (Mac → servidor) ----
# Roda NO MAC. O código viaja pelo git — o servidor tem o clone e compila lá — então esta
# função só orquestra: push, e do lado de lá backup → pull → restart → status.
#
# .env do Mac:  DEPLOY_HOST=usuario@servidor    DEPLOY_DIR=hsr-bot
# DEPLOY_DIR é relativo ao home do servidor (ou absoluto); nada de aspas/espaços no caminho.
cmd_deploy() {
  require_env
  [ -n "${DEPLOY_HOST:-}" ] || { err "DEPLOY_HOST vazio no .env (ex.: usuario@1.2.3.4)"; return 1; }
  local dir="${DEPLOY_DIR:-hsr-bot}"

  # Só o que está COMMITADO viaja. Sem esta trava você reinicia o servidor achando que subiu
  # a correção que ainda está só no editor, e passa a caçar um bug que já está resolvido.
  [ -z "$(git status --porcelain)" ] || {
    err "Há mudanças não commitadas — só commit viaja pro servidor."
    git status --short | sed 's/^/  /'; return 1; }

  step "Enviando $(git rev-parse --short HEAD) pro git"
  git push || return 1

  # Um `&&` só: o backup vem antes de tudo porque migração de Flyway roda no start e é a
  # única coisa que o git não desfaz. O `restart` já compila ANTES de derrubar — build
  # quebrado no servidor deixa o bot velho no ar em vez de deixar buraco.
  step "Deploy em $DEPLOY_HOST:$dir"
  ssh "$DEPLOY_HOST" "cd $dir && ./bot.sh backup && git pull --ff-only && ./bot.sh restart && ./bot.sh status" \
    || { err "Deploy falhou — o bot do servidor continua na versão anterior"; return 1; }
  ok "Deploy concluído"
}

# ---- pull-db (servidor → Mac) ----
# Traz uma cópia do banco de PRODUÇÃO pro Mac, pra reproduzir bug com dado real: guia_arte
# (arte que membros subiram), o placar do /rank, as tier lists, o histórico de conversa.
#
# SÓ NESTE SENTIDO. O caminho inverso — restaurar o dump do Mac no servidor — apagaria tudo
# isso e o flyway_schema_history junto; é a única perda irreversível possível aqui. Por isso
# a exigência de DEPLOY_HOST: essa chave só existe no .env do Mac, então um `pull-db` digitado
# na sessão ssh errada para na primeira linha em vez de dropar o banco de produção.
#
# Esquema NÃO viaja por aqui: migração de Flyway anda no git, com o código (ver cmd_deploy).
cmd_pull_db() {
  require_env
  [ -n "${DEPLOY_HOST:-}" ] || {
    err "DEPLOY_HOST vazio: pull-db roda no Mac (destino), não no servidor (origem)."
    echo "  Ele DROPA o banco local e põe o de produção no lugar." >&2; return 1; }
  local dir="${DEPLOY_DIR:-hsr-bot}"
  command -v dropdb >/dev/null 2>&1 || { err "dropdb não encontrado no PATH."; return 1; }
  start_postgres || return 1

  # Template explícito: o `-t` do mktemp GNU não aceita prefixo sem X's como o do BSD.
  local tmp; tmp="$(mktemp "${TMPDIR:-/tmp}/hsrbot-prod.XXXXXX")" || return 1
  trap 'rm -f "$tmp"' RETURN     # dump de produção tem dado de membro: não fica largado em /tmp

  step "Puxando o banco de $DEPLOY_HOST"
  # O pg_dump roda LÁ, com o .env de lá: a senha de produção não precisa existir neste .env.
  ssh "$DEPLOY_HOST" "cd $dir && set -a && . ./.env && set +a && PGPASSWORD=\$DB_PASSWORD pg_dump -h localhost -U \$DB_USER hsrbot | gzip" > "$tmp" || {
    err "dump remoto falhou — banco local intacto"; return 1; }
  # ssh que cai no meio deixa um .gz truncado, que restaura pela metade sem reclamar. Testar
  # ANTES do dropdb é o que separa "não deu, tente de novo" de ficar sem os dois lados.
  gunzip -t "$tmp" 2>/dev/null || { err "dump veio incompleto — banco local intacto"; return 1; }
  ok "Dump recebido ($(du -h "$tmp" | cut -f1))"

  # Rede de segurança do lado de cá, depois do dump remoto ter dado certo: não adianta gastar
  # 2s dumpando o local se o remoto ia falhar. `|| true`: banco local vazio/recém-criado ainda
  # não tem o que salvar, e isso não é motivo pra abortar.
  step "Backup do banco LOCAL antes de sobrescrever"
  cmd_backup || warn "backup local falhou — seguindo assim mesmo"

  # dropdb não passa com conexão aberta, e o bot local segura um pool inteiro.
  local was_running=0; bot_running && { was_running=1; stop_bot; }

  step "Restaurando no banco local"
  # A extensão vai embora com o banco e NÃO é trusted: sem recriá-la como superuser antes do
  # restore, toda linha de vector_store falha (ver SETUP.md §3).
  dropdb --if-exists hsrbot && createdb -O "$DB_USER" hsrbot \
    && psql -qd hsrbot -c "CREATE EXTENSION IF NOT EXISTS vector;" >/dev/null || {
    err "não consegui recriar o banco local — recrie à mão (SETUP.md §2 e §3)"; return 1; }
  if gunzip -c "$tmp" | psql -qd hsrbot >/dev/null; then
    ok "Banco local agora é a cópia da produção"
  else
    err "restore falhou — banco local pode estar pela metade; o dump anterior está em backups/"
    [ "$was_running" = 1 ] && warn "bot NÃO reiniciado de propósito"
    return 1
  fi
  [ "$was_running" = 1 ] && { warn "Bot estava rodando antes — reiniciando"; start_bot; }
  return 0
}

case "${1:-}" in
  start)   cmd_start ;;
  restart) cmd_restart ;;
  stop)    shift; cmd_stop "${1:-}" ;;
  status)  cmd_status ;;
  activity) shift; cmd_activity "$@" ;;
  --model|model) cmd_model ;;
  logs)    cmd_logs ;;
  reindex) cmd_reindex ;;
  heartbeat) cmd_heartbeat ;;
  backup)  cmd_backup ;;
  deploy)  cmd_deploy ;;
  pull-db) cmd_pull_db ;;
  *) echo "uso: ./bot.sh {start|restart|stop [--all]|status|activity [tipo] \"texto\"|--model|logs|reindex|heartbeat|backup|deploy|pull-db}"; exit 1 ;;
esac
