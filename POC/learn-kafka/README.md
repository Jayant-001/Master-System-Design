
## Setup Steps
1. Start kafka
    
    `finch compose up -d`
2. Create a topic

    `finch exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic orders --partitions 3`

