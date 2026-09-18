package main

/*
#cgo LDFLAGS: -llog

#include "bridge.h"
*/
import "C"

import (
	"runtime"
	"runtime/debug"
	"sync"

	"cfa/native/config"
	"cfa/native/delegate"
	"cfa/native/tunnel"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/log"
)

var networkChangeMu sync.Mutex

func main() {
	panic("Stub!")
}

//export coreInit
func coreInit(home, versionName, gitVersion C.c_string, sdkVersion C.int) {
	h := C.GoString(home)
	v := C.GoString(versionName)
	g := C.GoString(gitVersion)
	s := int(sdkVersion)

	delegate.Init(h, v, g, s)

	reset()
}

//export reset
func reset() {
	config.LoadDefault()
	tunnel.ResetStatistic()
	tunnel.CloseAllConnections()

	runtime.GC()
	debug.FreeOSMemory()
}

//export forceGc
func forceGc() {
	go func() {
		log.Infoln("[APP] request force GC")

		runtime.GC()
		debug.FreeOSMemory()
	}()
}

//export notifyNetworkChanged
func notifyNetworkChanged() {
	networkChangeMu.Lock()
	defer networkChangeMu.Unlock()

	log.Infoln("[APP] underlying network changed, resetting connections")

	resolver.ResetConnectionSync()
	tunnel.CloseAllConnections()
}
