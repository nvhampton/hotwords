# Hotwords EC2 Deployment

Deploy Hotwords to AWS EC2 with Docker and automatic HTTPS via Caddy.

## Prerequisites

- AWS account
- Domain registered on Cloudflare (or any DNS provider)

## Cost Estimate

- **EC2 t3.micro**: Free (AWS Free Tier) or ~$8/month
- **Domain**: $2-10/year (cheap TLDs like .site, .xyz)
- **HTTPS**: Free (Let's Encrypt via Caddy)

## Step 1: Launch EC2 Instance

1. Go to AWS Console → EC2 → Launch Instance
2. Settings:
   - **Name**: hotwords
   - **AMI**: Amazon Linux 2023 (free tier eligible)
   - **Instance type**: t3.micro (free tier) or t4g.nano ($3/mo)
   - **Key pair**: Create or select existing (you'll need this to SSH)
   - **Security group**: Create new with these rules:
     - SSH (22) from your IP
     - HTTP (80) from anywhere
     - HTTPS (443) from anywhere
3. Launch and note the **Public IP**

## Step 2: Configure DNS (Cloudflare)

1. Add your domain to Cloudflare (if not already)
2. Add an **A record**:
   - Name: `@` (or subdomain like `play`)
   - IPv4: Your EC2 public IP
   - Proxy status: **DNS only** (grey cloud) - important for Let's Encrypt
3. Wait a few minutes for DNS propagation

## Step 3: Set Up EC2 Instance

SSH into your instance:
```bash
ssh -i ~/Downloads/mysecurekeypair.pem ec2-user@100.23.220.251
```

Clone the repo and run setup:
```bash
git clone https://github.com/nvhampton/hotwords.git
cd hotwords/hotwords/deploy
chmod +x setup.sh deploy.sh
./setup.sh
```

Log out and back in (for Docker permissions):
```bash
exit
ssh -i ~/Downloads/mysecurekeypair.pem ec2-user@100.23.220.251
```

## Step 4: Configure and Deploy

Edit the Caddyfile with your domain:
```bash
cd ~/hotwords/hotwords/deploy
nano Caddyfile
# Replace YOUR_DOMAIN with your actual domain (e.g., hotwords.site)
```

Deploy:
```bash
./deploy.sh
```

## Step 5: Verify

Visit `https://yourdomain.com` - you should see Hotwords with a valid HTTPS certificate.

## Updating

To deploy updates:
```bash
cd ~/hotwords
git pull
cd hotwords/deploy
./deploy.sh
```

## Troubleshooting

**Check logs:**
```bash
cd ~/hotwords/hotwords/deploy
docker-compose logs -f
```

**Restart services:**
```bash
docker-compose restart
```

**Check if services are running:**
```bash
docker-compose ps
```

**Certificate issues:**
- Ensure Cloudflare proxy is OFF (grey cloud, DNS only)
- Ensure ports 80 and 443 are open in EC2 security group
- Check Caddy logs: `docker-compose logs caddy`
