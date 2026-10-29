package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"

	"github.com/segmentio/kafka-go"
)

func main() {

	// ctx is cancelled when you press Ctrl+C, so we can shut down cleanly.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	r := kafka.NewReader(kafka.ReaderConfig{
		Brokers: 	[]string{"localhost:9092"},
		Topic: 		"orders",
		GroupID: 	"order-service",	// consumer group: Kafka remembers how far this group has read
	})

	defer r.Close()
	fmt.Println("waiting for messages... (Ctrl+C to stop)")

	for {
		m, err := r.ReadMessage(ctx) // blocks until a message arrives; commits the offset after
		if err != nil {
			if ctx.Err() != nil {
				return // Ctrl + C
			}
			log.Fatalf("read failed: %v", err)
		}
		
		fmt.Printf("partition=%d offset=%d key=%s value=%s\n", m.Partition, m.Offset, m.Key, m.Value)
	}
}