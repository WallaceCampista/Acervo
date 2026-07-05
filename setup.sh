#!/usr/bin/env bash
# Acervo — setup de desenvolvimento local.
# Rode este script na raiz do projeto para preparar o ambiente de forma
# automática: valida Java 21 e Maven, gera o .env (compose de produção),
# baixa as dependências e compila, sobe o Postgres + pgvector e verifica o
# servidor de IA (LM Studio / Ollama).
#
# Uso:
#   ./setup.sh                 # setup completo
#   ./setup.sh -y              # não-interativo (auto-instala no macOS/brew)
#   ./setup.sh --no-docker     # não sobe o Postgres
#   ./setup.sh -h              # ajuda
#
# Idempotente: pode ser executado várias vezes com segurança.
# Observação: o servidor de IA (LM Studio/Ollama) é externo e precisa ser
# iniciado por você — o script apenas verifica se está no ar.

set -eo pipefail

# ------------------------------------------------------------------------------
# Flags
# ------------------------------------------------------------------------------
ASSUME_YES=false
SKIP_DOCKER=false

for arg in "$@"; do
    case "$arg" in
        -y|--yes)     ASSUME_YES=true ;;
        --no-docker)  SKIP_DOCKER=true ;;
        -h|--help)
            awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
            exit 0
            ;;
        *)
            echo "Argumento desconhecido: $arg (use -h para ajuda)" >&2
            exit 1
            ;;
    esac
done

# ------------------------------------------------------------------------------
# Plataforma
# ------------------------------------------------------------------------------
case "$(uname -s)" in
    Linux*)              PLATFORM="linux" ;;
    Darwin*)             PLATFORM="macos" ;;
    MINGW*|MSYS*|CYGWIN*) PLATFORM="windows" ;;
    *)                   PLATFORM="unknown" ;;
esac

if [ "$PLATFORM" = "windows" ]; then
    echo "=========================================="
    echo " Windows detectado ($(uname -s))"
    echo "=========================================="
    echo ""
    echo "Este projeto (Java + Docker) deve ser configurado dentro do WSL2 —"
    echo "shells nativos do Windows (Git Bash/Cygwin) não têm suporte confiável"
    echo "a Docker e caminhos POSIX."
    echo ""
    echo "Passos (uma vez):"
    echo "  1. No PowerShell (Admin):   wsl --install"
    echo "  2. Reinicie e abra o Ubuntu (WSL)."
    echo "  3. Instale o Docker Desktop com integração WSL2 habilitada."
    echo "  4. Clone o repositório DENTRO do WSL (não em /mnt/c) e rode"
    echo "     ./setup.sh lá — ele roda como Linux."
    echo ""
    exit 1
fi
if [ "$PLATFORM" = "unknown" ]; then
    echo "ERRO: plataforma não suportada: $(uname -s)"
    exit 1
fi

# ------------------------------------------------------------------------------
# Saída colorida
# ------------------------------------------------------------------------------
if [ -t 1 ]; then
    RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
else
    RED=''; GREEN=''; YELLOW=''; BLUE=''; NC=''
fi

print_success() { echo -e "${GREEN}[OK]${NC} $1"; }
print_error()   { echo -e "${RED}[ERRO]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[!]${NC} $1"; }
print_info()    { echo -e "${BLUE}[i]${NC} $1"; }
print_header() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

# ------------------------------------------------------------------------------
# Progresso (etapa n/total + barra + porcentagem)
# ------------------------------------------------------------------------------
BAR_WIDTH=30
STEP=0

# Total de etapas — o Postgres pode ser pulado com --no-docker.
TOTAL_STEPS=5   # Java, Maven, .env, build, servidor de IA
[ "$SKIP_DOCKER" = false ] && TOTAL_STEPS=$((TOTAL_STEPS + 1))   # Postgres

draw_bar() { # $1 = porcentagem (0-100)
    local pct="$1" filled i out=""
    filled=$(( pct * BAR_WIDTH / 100 ))
    for ((i = 0; i < BAR_WIDTH; i++)); do
        if ((i < filled)); then out+="█"; else out+="░"; fi
    done
    printf '%s' "$out"
}

progress_header() { # $1 = título da etapa
    STEP=$((STEP + 1))
    local pct
    pct=$(( STEP * 100 / TOTAL_STEPS ))
    (( pct > 100 )) && pct=100
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}[${STEP}/${TOTAL_STEPS}] ${1}${NC}"
    echo -e "  ${GREEN}$(draw_bar "$pct")${NC} ${pct}%"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

trap 'echo; print_error "Setup interrompido (linha $LINENO). Corrija o problema acima e rode novamente."' ERR

# ------------------------------------------------------------------------------
# Raiz do projeto
# ------------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -f "pom.xml" ] || ! grep -q "<artifactId>acervo</artifactId>" pom.xml; then
    print_error "Rode este script na raiz do projeto Acervo (pom.xml do artefato 'acervo' não encontrado)."
    exit 1
fi

print_header "Acervo — Setup de Desenvolvimento"

# ==============================================================================
# 1. Java 21
# ==============================================================================
progress_header "Java 21 (JDK)"

REQUIRED_JAVA=21

java_install_hint() {
    print_info "Instale o JDK ${REQUIRED_JAVA} (Temurin recomendado):"
    print_info "  • SDKMAN (multiplataforma): curl -s \"https://get.sdkman.io\" | bash && sdk install java ${REQUIRED_JAVA}-tem"
    if [ "$PLATFORM" = "macos" ]; then
        print_info "  • Homebrew: brew install openjdk@${REQUIRED_JAVA} (siga o aviso de symlink/PATH exibido pelo brew)"
    else
        print_info "  • Ou baixe em: https://adoptium.net/"
    fi
}

if ! command -v java &> /dev/null; then
    print_error "Java não está instalado."
    java_install_hint
    exit 1
fi

JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)?.*/\1/')"
if [ -z "$JAVA_MAJOR" ] || ! [ "$JAVA_MAJOR" -eq "$JAVA_MAJOR" ] 2>/dev/null; then
    print_warning "Não foi possível detectar a versão do Java — verifique manualmente ($(java -version 2>&1 | head -1))."
elif [ "$JAVA_MAJOR" -ge "$REQUIRED_JAVA" ]; then
    print_success "Java $(java -version 2>&1 | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
else
    print_error "Java $JAVA_MAJOR é muito antigo (requer $REQUIRED_JAVA+)."
    java_install_hint
    exit 1
fi

# ==============================================================================
# 2. Maven
# ==============================================================================
progress_header "Maven"

MVN="mvn"
[ -x "./mvnw" ] && MVN="./mvnw"

if [ "$MVN" = "./mvnw" ]; then
    print_success "Usando o Maven Wrapper (./mvnw)"
elif command -v mvn &> /dev/null; then
    print_success "Maven $(mvn -v 2>/dev/null | head -1 | awk '{print $3}')"
else
    print_error "Maven não está instalado (e não há ./mvnw)."
    if [ "$PLATFORM" = "macos" ] && command -v brew &> /dev/null && [ "$ASSUME_YES" = true ]; then
        print_info "Instalando Maven via Homebrew..."
        brew install maven && MVN="mvn" && print_success "Maven instalado"
    elif [ "$PLATFORM" = "macos" ] && command -v brew &> /dev/null; then
        print_info "Instale com: brew install maven"
        exit 1
    else
        print_info "Instale com o SDKMAN: sdk install maven — ou pelo gerenciador da sua distro."
        exit 1
    fi
fi

# ==============================================================================
# 3. Arquivo .env (compose de produção)
# ==============================================================================
progress_header "Configuração (.env para produção)"

print_info "Em DEV a app usa os defaults de application-dev.yml — o .env é para o docker-compose.prod.yml."

if [ ! -f ".env" ]; then
    if [ -f ".env.example" ]; then
        cp .env.example .env
        print_success ".env criado a partir do .env.example"
    else
        print_warning ".env.example não encontrado — pulando (.env não é necessário para DEV)."
    fi
else
    print_success ".env já existe (preservado)"
fi

# Troca o placeholder de senha do Postgres por uma senha forte URL-safe (hex),
# só se ainda estiver como 'changeme'. Afeta apenas o compose de produção.
if [ -f ".env" ] && grep -q "^POSTGRES_PASSWORD=changeme$" .env && command -v openssl &> /dev/null; then
    PWD_HEX="$(openssl rand -hex 24)"
    if [ "$PLATFORM" = "macos" ]; then
        sed -i '' "s|^POSTGRES_PASSWORD=changeme$|POSTGRES_PASSWORD=${PWD_HEX}|" .env
    else
        sed -i "s|^POSTGRES_PASSWORD=changeme$|POSTGRES_PASSWORD=${PWD_HEX}|" .env
    fi
    print_success "POSTGRES_PASSWORD (produção) gerado no .env"
fi

# ==============================================================================
# 4. Dependências & build (Maven)
# ==============================================================================
progress_header "Dependências & build (Maven)"

print_info "Baixando dependências e compilando (pode demorar na primeira vez)..."
if $MVN -B -DskipTests package; then
    print_success "Build concluído (JAR em target/)"
else
    print_warning "O build falhou — verifique os erros acima."
fi

# ==============================================================================
# 5. Banco de dados (Postgres + pgvector)
# ==============================================================================
if [ "$SKIP_DOCKER" = false ]; then
    progress_header "Banco de dados (Postgres + pgvector)"

    COMPOSE=""
    if ! command -v docker &> /dev/null; then
        print_warning "Docker não está instalado. Instale: https://docs.docker.com/get-docker/"
        SKIP_DOCKER=true
    elif ! docker info &> /dev/null; then
        print_warning "Docker está instalado mas não respondeu."
        if [ "$PLATFORM" = "linux" ]; then
            print_info "No Linux: 'sudo systemctl start docker' e adicione seu usuário ao grupo docker"
            print_info "('sudo usermod -aG docker \$USER' e reabra a sessão)."
        else
            print_info "Inicie o Docker Desktop e rode o setup novamente."
        fi
        SKIP_DOCKER=true
    elif docker compose version &> /dev/null; then
        COMPOSE="docker compose"
    elif command -v docker-compose &> /dev/null; then
        COMPOSE="docker-compose"
    else
        print_warning "Docker Compose não encontrado."
        SKIP_DOCKER=true
    fi

    if [ "$SKIP_DOCKER" = false ]; then
        print_info "Subindo o Postgres (pgvector/pgvector:pg16)..."
        $COMPOSE up -d
        print_success "Container iniciado"

        print_info "Aguardando o Postgres ficar pronto..."
        ready=false
        for _ in $(seq 1 60); do
            if $COMPOSE exec -T postgres pg_isready -U acervo -d acervo &> /dev/null; then
                ready=true; break
            fi
            sleep 1
        done
        if [ "$ready" = true ]; then
            print_success "Postgres pronto (o schema é criado no primeiro boot da aplicação)"
        else
            print_warning "Postgres não respondeu a tempo — rode 'docker compose up -d' manualmente depois."
        fi
    fi
else
    print_info "(Postgres pulado por --no-docker)"
fi

# ==============================================================================
# 6. Servidor de IA (verificação — externo)
# ==============================================================================
progress_header "Servidor de IA (LM Studio / Ollama)"

AI_URL="${ACERVO_AI_BASE_URL:-http://localhost:1234}"
print_info "Verificando servidor OpenAI-compat em ${AI_URL} ..."
if command -v curl &> /dev/null && curl -fsS --max-time 3 "${AI_URL}/v1/models" &> /dev/null; then
    print_success "Servidor de IA respondeu em ${AI_URL}"
else
    print_warning "Servidor de IA não respondeu em ${AI_URL}/v1/models (é externo — precisa ser iniciado por você)."
    print_info "Suba o LM Studio (ou Ollama/vLLM) e carregue DOIS modelos:"
    print_info "  • chat (ex.: gemma-2-9b-it) — context length ≥ 16k"
    print_info "  • embedding (ex.: nomic-embed-text-v1.5, 768 dims)"
    print_info "Depois inicie o Local Server na porta 1234."
fi

# ==============================================================================
# Resumo
# ==============================================================================
trap - ERR
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Setup concluído${NC}"
echo -e "  ${GREEN}$(draw_bar 100)${NC} 100%"
echo -e "${BLUE}========================================${NC}"
echo ""

echo "Próximos passos:"
echo ""
echo -e "  1. Suba o servidor de IA (LM Studio/Ollama) com os modelos de chat + embedding em ${GREEN}:1234${NC}"
if [ "$SKIP_DOCKER" = true ]; then
    echo -e "  2. Suba o Postgres:       ${GREEN}docker compose up -d${NC}"
    echo -e "  3. Rode a aplicação:      ${GREEN}${MVN} spring-boot:run${NC}"
    echo -e "  4. Acesse:                ${GREEN}http://localhost:8080${NC}"
else
    echo -e "  2. Rode a aplicação:      ${GREEN}${MVN} spring-boot:run${NC}"
    echo -e "  3. Acesse:                ${GREEN}http://localhost:8080${NC}"
fi
echo ""
echo -e "  Usuário root inicial: ${GREEN}root@acervo.local${NC} — defina a senha em ${GREEN}ACERVO_BOOTSTRAP_ROOT_PASSWORD${NC} antes do 1º boot."
echo -e "  Postgres dev:  ${GREEN}docker compose up -d${NC} / ${GREEN}docker compose down${NC}   |   Testes: ${GREEN}${MVN} test${NC}"
echo ""
print_success "Ambiente pronto. Bom trabalho!"
