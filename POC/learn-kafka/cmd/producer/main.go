package main

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/segmentio/kafka-go"
)

func main() {

	w := &kafka.Writer{
		Addr: 			kafka.TCP("localhost:9092"),
		Topic: 			"orders",
		Balancer: 		&kafka.Hash{},
		BatchTimeout: 	10 * time.Millisecond,
		RequiredAcks: 	kafka.RequireAll,
	}

	defer w.Close()

	for i := 1; i <= 5; i++ {
		msg := kafka.Message{
			Key: 	[]byte(fmt.Sprintf("order-%d", i)),
			Value: 	[]byte(fmt.Sprintf("order %d placed at %s", i, time.Now().Format(time.TimeOnly))),
		}
		if err := w.WriteMessages(context.Background(), msg); err != nil {
			log.Fatalf("Write failed: %v", err)
		}

		fmt.Println("sent:", string(msg.Key))
	}
}