# ISO8583Studio - Docker + noVNC Deployment
# Multi-stage build for minimal image size

# ============================================
# Stage 1: Build the application
# ============================================
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Copy gradle files first for better caching
COPY gradle gradle
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY composeApp/build.gradle.kts composeApp/
COPY cryptocalc/build.gradle.kts cryptocalc/
COPY iso-core-lib/build.gradle.kts iso-core-lib/

# Make gradlew executable
RUN chmod +x gradlew

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY composeApp/src composeApp/src
COPY composeApp/resources composeApp/resources
COPY cryptocalc/src cryptocalc/src
COPY iso-core-lib/src iso-core-lib/src

# Build the uber JAR for desktop
RUN ./gradlew :composeApp:packageUberJarForCurrentOS --no-daemon

# ============================================
# Stage 2: Runtime with noVNC
# ============================================
FROM eclipse-temurin:21-jre

LABEL maintainer="POS Expert Solutions Pvt Ltd"
LABEL description="ISO8583Studio Demo - noVNC Remote Desktop"

# Install X11, VNC, noVNC and dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    xvfb \
    x11vnc \
    novnc \
    websockify \
    supervisor \
    libxrender1 \
    libxtst6 \
    libxi6 \
    libxext6 \
    libx11-6 \
    libfreetype6 \
    fontconfig \
    fonts-dejavu-core \
    fonts-liberation \
    openbox \
    dbus-x11 \
    xdg-utils \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create app user for security
RUN useradd -m -s /bin/bash appuser

# Set environment variables
ENV DISPLAY=:99
ENV RESOLUTION=1280x800x24
ENV VNC_PORT=5900
ENV NOVNC_PORT=6080
ENV HOME=/home/appuser

# Create necessary directories
RUN mkdir -p /app /var/log/supervisor /home/appuser/.vnc /home/appuser/.config/openbox

# Copy the built JAR from builder stage
COPY --from=builder /app/composeApp/build/compose/jars/*.jar /app/iso8583studio.jar

# Copy startup scripts
COPY scripts/start.sh /app/start.sh
COPY scripts/supervisord.conf /etc/supervisor/conf.d/supervisord.conf
COPY scripts/openbox-rc.xml /home/appuser/.config/openbox/rc.xml

RUN chmod +x /app/start.sh && \
    chown -R appuser:appuser /home/appuser /app

# Expose noVNC web port
EXPOSE 6080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -f http://localhost:6080/ || exit 1

USER appuser
WORKDIR /home/appuser

CMD ["/app/start.sh"]
