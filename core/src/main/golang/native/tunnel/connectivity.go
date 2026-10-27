package tunnel

import (
	"context"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

func HealthCheck(name string) {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request health check for `%s`: not found", name)

		return
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request health check for `%s`: invalid type %s", name, p.Type().String())

		return
	}

	wg := &sync.WaitGroup{}

	for _, pr := range g.Providers() {
		wg.Add(1)

		go func(provider provider.ProxyProvider) {
			provider.HealthCheck()

			wg.Done()
		}(pr)
	}

	wg.Wait()
}

func HealthCheckAll() {
	for _, g := range QueryProxyGroupNames(false) {
		go func(group string) {
			HealthCheck(group)
		}(g)
	}
}

// FEATURE-4 BEGIN: single-proxy delay test. Return values:
// >= 0: delay in ms; -1: tested and failed; -2: no such proxy, or the
// name is a group (groups are tested with HealthCheck, not here).
// The result is judged by AliveForTestUrl, NOT by URLTest's error alone:
// URLTest returns err == nil with a measured delay even when the HTTP
// status is unsatisfying, while its recorder marks the proxy not-alive
// in that case. Returning the raw delay there would show a fake Nms
// for a failed node. A side effect (intended): the test writes a delay
// history entry, so the tri-state display updates on the next query.
func TestProxy(name string) int {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request single test for `%s`: not found", name)
		return -2
	}

	if _, ok := p.Adapter().(outboundgroup.ProxyGroup); ok {
		log.Warnln("Request single test for `%s`: is a group", name)
		return -2
	}

	const testURL = "https://www.gstatic.com/generate_204"

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	delay, _ := p.URLTest(ctx, testURL, nil)

	if !p.AliveForTestUrl(testURL) {
		return -1
	}

	return int(delay)
}

// FEATURE-4 END
