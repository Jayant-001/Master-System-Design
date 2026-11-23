package clock

type Lamport struct {
	Time uint64
	Node string
}

// local event or send: move forward, return new time
func (c *Lamport) Tick() uint64 {
	c.Time++
	return c.Time
}

// message received: apply the max rule, return new time
func (c *Lamport) Receive(remote uint64) uint64 {
	c.Time = max(remote, c.Time) + 1
	return c.Time
}


// read the current time without changing it
func (c *Lamport) Now() uint64 {
	return c.Time
}


// Create a new Clock
func NewLamport(node string) *Lamport {
	return &Lamport {
		Time: 0,
		Node: node,
	}
}

