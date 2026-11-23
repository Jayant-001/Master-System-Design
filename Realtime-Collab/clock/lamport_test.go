package clock

import (
	"sync"
	"testing"
)

func TestLamportBasic(t *testing.T) {
	c := NewLamport("node-1")

	if got := c.Tick(); got != 1 {
		t.Fatalf("Tick() = %d, want 1", got)
	}
	if got := c.Receive(10); got != 11 {
		t.Fatalf("Receive(10) = %d, want 11", got)
	}
	if got := c.Receive(3); got != 12 {
		t.Fatalf("Receive(3) = %d, want 12", got)
	}
	if got := c.Now(); got != 12 {
		t.Fatalf("Now() = %d, want 12", got)
	}
}

func TestLamportConcurrent(t *testing.T) {
	c := NewLamport("node-a")
	var wg sync.WaitGroup

	// Spawn multiple goroutines hammering Tick, Receive, and Now together
	for g := 0; g < 10; g++ {
		wg.Add(1)
		go func(id uint64) {
			defer wg.Done()
			for i := 0; i < 100; i++ {
				c.Tick()
				c.Receive(id*10 + uint64(i))
				_ = c.Now()
			}
		}(uint64(g))
	}

	wg.Wait()
}