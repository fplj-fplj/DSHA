package connectcore

import "github.com/libp2p/go-libp2p/core/network"

// ConnectionEpoch changes when a connection that carried authenticated app
// traffic disconnects. App hello/authorization must capture and compare it.
func (n *Node) ConnectionEpoch() int64 { n.mu.Lock(); defer n.mu.Unlock(); return n.connectionEpoch }
func (n *Node) IsConnected() bool      { return n.Route() != "disconnected" }
func (n *Node) disconnected(c network.Conn) {
	n.mu.Lock()
	if !n.boundConnections[c.ID()] {
		n.mu.Unlock()
		return
	}
	delete(n.boundConnections, c.ID())
	n.connectionEpoch++
	n.broken = true
	var retired []*Stream
	for s := range n.streams {
		retired = append(retired, s)
	}
	n.mu.Unlock()
	for _, s := range retired {
		_ = s.Reset()
	}
}
