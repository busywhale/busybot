## BusyBot
A simple bot implementation for BusyWhale RFQ platform.

### Running BusyBot in Docker
1. Building docker image

   `cd docker; docker build -f busybot_build -t busybot:local ../`

2. Configuring Docker-Compose `docker-compose.yml` and `.env`

3. Starting all bots:

   `docker compose up -d`

   Starting individual bot (maker bot for example): 

   `docker compose up -d busybot-2`

4. Checking log files for individual bot: 
 
   `docker compose logs -f busybot-2`

5. Stopping all bots: 

   `docker compose down`

   Stopping individual bot (maker bot for example): 

   `docker compose stop busybot-2`
