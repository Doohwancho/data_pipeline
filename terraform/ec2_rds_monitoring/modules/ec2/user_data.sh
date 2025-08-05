#!/bin/bash

export RDS_ENDPOINT=${rds_endpoint}
export RDS_USERNAME=${rds_username}
export RDS_PASSWORD=${rds_password}
export RDS_DATABASE=${rds_database}
export RDS_PORT=${rds_port}

# Check if the variables are set
if [ -z "$RDS_ENDPOINT" ] || [ -z "$RDS_USERNAME" ] || [ -z "$RDS_PASSWORD" ] || [ -z "$RDS_DATABASE" ] || [ -z "$RDS_PORT" ]; then
    echo "One or more required environment variables are not set. Aborting the script."
    exit 1
fi

# Update system and install Java 8 for AMD64
sudo apt-get update
sudo apt-get install -y openjdk-8-jdk

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

cd /home/ubuntu/
sudo git clone --depth 1 https://github.com/Doohwancho/data_pipeline.git

cd ecommerce
sudo chown -R ubuntu:ubuntu .

cd /home/ubuntu/data_pipeline/pipeline1/
chmod +x gradlew
sudo ./gradlew build -x test

# Proceed to run the application if all checks pass
nohup java -jar \
  -Dspring.profiles.active=prod \
  -DRDS_ENDPOINT=$RDS_ENDPOINT \
  -DRDS_USERNAME=$RDS_USERNAME \
  -DRDS_PASSWORD=$RDS_PASSWORD \
  -DRDS_DATABASE=$RDS_DATABASE \
  -DRDS_PORT=$RDS_PORT \
  ./build/libs/denormalized_ecommerce-0.0.1-SNAPSHOT.jar > application.log 2>&1 &
