# ISO8583Studio - Remote Demo Deployment

Deploy ISO8583Studio for remote client demos using Docker + noVNC.

## 🚀 Quick Start

### Local Testing

```bash
# Build and run locally
cd deploy
docker-compose up --build

# Access at: http://localhost:6080
# Click "Connect" in browser
```

### With Mock ISO8583 Host (for self-contained demos)

```bash
docker-compose --profile with-mock up --build
# App: http://localhost:6080
# Mock Host: localhost:8583
```

---

## ☁️ Free Cloud Deployment

### Option 1: Render.com (Recommended)

1. **Fork/Push** this repo to GitHub
2. Go to [render.com](https://render.com) → New → Web Service
3. Connect your GitHub repo
4. Settings:
   - **Root Directory:** `.` (root)
   - **Dockerfile Path:** `deploy/Dockerfile`
   - **Instance Type:** Free
5. Deploy!

**Free Tier:** 750 hours/month, sleeps after 15 min idle, auto-wakes on request.

### Option 2: Railway.app

1. Go to [railway.app](https://railway.app) → New Project
2. Deploy from GitHub repo
3. Railway auto-detects `railway.json`

**Free Tier:** $5 credit/month (~500 hours).

### Option 3: Fly.io

```bash
# Install flyctl
curl -L https://fly.io/install.sh | sh

# Deploy
cd <project-root>
flyctl launch --config deploy/fly.toml

# Get URL
flyctl status
```

**Free Tier:** 3 shared VMs, 256MB each.

---

## 📊 Resource Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| RAM | 384 MB | 512 MB |
| CPU | 0.5 vCPU | 1 vCPU |
| Storage | 500 MB | 1 GB |

---

## 🔧 Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `RESOLUTION` | `1280x800x24` | Screen resolution (WxHxDepth) |
| `TZ` | `UTC` | Timezone |
| `NOVNC_PORT` | `6080` | noVNC web port |

### Customizing Resolution

For higher resolution demos:

```bash
docker run -e RESOLUTION=1920x1080x24 -p 6080:6080 iso8583studio-demo
```

---

## 🏗️ Build Options

### Full Build (includes Gradle build)

```bash
docker build -f deploy/Dockerfile -t iso8583studio-demo .
```

### Lite Build (pre-built JAR)

Faster builds when JAR already exists:

```bash
# First, build the JAR locally
./gradlew :composeApp:packageUberJarForCurrentOS

# Then build lite image
docker build -f deploy/Dockerfile.lite -t iso8583studio-demo .
```

---

## 🔐 Security Notes

- **Demo Only:** This setup has no authentication
- **Production:** Add nginx with basic auth or VNC password
- The container runs as non-root user `appuser`

### Adding Basic Auth (optional)

```dockerfile
# In Dockerfile, add nginx:
RUN apt-get install -y nginx apache2-utils
RUN htpasswd -bc /etc/nginx/.htpasswd demo demo123
```

---

## 🐛 Troubleshooting

### Black Screen

```bash
# Check if Xvfb is running
docker exec -it iso8583studio-demo ps aux | grep Xvfb
```

### App Not Starting

```bash
# Check Java process
docker exec -it iso8583studio-demo ps aux | grep java

# View logs
docker logs iso8583studio-demo
```

### Memory Issues

Free tiers have 512MB limit. If OOM:
- Reduce `-Xmx` in start.sh
- Lower resolution: `RESOLUTION=1024x768x16`

---

## 📁 Files

```
deploy/
├── Dockerfile          # Full multi-stage build
├── Dockerfile.lite     # Lite build (pre-built JAR)
├── docker-compose.yml  # Local development
├── render.yaml         # Render.com config
├── railway.json        # Railway.app config
├── fly.toml            # Fly.io config
├── .dockerignore       # Docker build exclusions
└── scripts/
    ├── start.sh        # Container entrypoint
    ├── supervisord.conf # Process manager (alternative)
    └── openbox-rc.xml  # Window manager config
```
