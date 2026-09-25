import dnssd
import Foundation

/// Finding devices on the network, and being found: DNS-SD, service `_arsivinyo._tcp`.
///
/// The records are the phone's: the instance name is the first sixteen characters of the
/// fingerprint, and the TXT record carries `v=1`, `id=<fingerprint>` and `name=<name>`.
/// Discovery says a device exists; it says nothing about its library. The name is shown,
/// never trusted: identity is settled by the `auth` signature once connected.
public final class Discovery: @unchecked Sendable {
    public struct Found: Hashable, Sendable, Identifiable {
        public let fingerprint: String
        public let name: String
        public let host: String
        public let port: UInt16
        public var id: String { fingerprint }
    }

    public var onChange: (() -> Void)?

    private let queue = DispatchQueue(label: "arsivinyo.discovery")
    private var ownFingerprint = ""
    private var registration: DNSServiceRef?
    private var browser: DNSServiceRef?
    private var resolving: [String: (ref: DNSServiceRef, context: Unmanaged<Resolve>)] = [:]
    private var found: [String: Found] = [:]
    private let lock = NSLock()

    /// What a resolve needs to report back: which service, to which discovery.
    private final class Resolve {
        weak var discovery: Discovery?
        let instance: String
        init(_ discovery: Discovery, _ instance: String) {
            self.discovery = discovery
            self.instance = instance
        }
    }

    public init() {}

    deinit { stop() }

    public var peers: [Found] { lock.withLock { Array(found.values) } }

    /// Announces this device and starts looking for others. Calling it again re-announces,
    /// which is how a new name reaches peers that cached the old one.
    public func start(fingerprint: String, name: String, port: UInt16) {
        queue.sync {
            stopLocked()
            ownFingerprint = fingerprint
            register(fingerprint: fingerprint, name: name, port: port)
            browse()
        }
    }

    public func stop() {
        queue.sync { stopLocked() }
    }

    private func stopLocked() {
        if let registration { DNSServiceRefDeallocate(registration) }
        if let browser { DNSServiceRefDeallocate(browser) }
        registration = nil
        browser = nil
        for (_, entry) in resolving {
            DNSServiceRefDeallocate(entry.ref)
            entry.context.release()
        }
        resolving = [:]
        lock.withLock { found = [:] }
    }

    private func register(fingerprint: String, name: String, port: UInt16) {
        var txt = TXTRecordRef()
        TXTRecordCreate(&txt, 0, nil)
        defer { TXTRecordDeallocate(&txt) }
        func set(_ key: String, _ value: String) {
            let bytes = Array(value.utf8.prefix(200))
            _ = bytes.withUnsafeBufferPointer { TXTRecordSetValue(&txt, key, UInt8(bytes.count), $0.baseAddress) }
        }
        set("v", "1")
        set("id", fingerprint)
        set("name", name)

        var ref: DNSServiceRef?
        let instance = String(fingerprint.prefix(16))
        let error = DNSServiceRegister(&ref, 0, 0, instance, Pairing.serviceType, nil, nil, port.bigEndian,
                                       TXTRecordGetLength(&txt), TXTRecordGetBytesPtr(&txt),
                                       { _, _, _, _, _, _, _ in }, nil)
        guard error == kDNSServiceErr_NoError, let ref else { return }
        DNSServiceSetDispatchQueue(ref, queue)
        registration = ref
    }

    private func browse() {
        var ref: DNSServiceRef?
        let context = Unmanaged.passUnretained(self).toOpaque()
        let error = DNSServiceBrowse(&ref, 0, 0, Pairing.serviceType, nil, { _, flags, interface, error, name, type, domain, context in
            guard error == kDNSServiceErr_NoError, let context, let name, let type, let domain else { return }
            let discovery = Unmanaged<Discovery>.fromOpaque(context).takeUnretainedValue()
            let instance = String(cString: name)
            if flags & kDNSServiceFlagsAdd != 0 {
                discovery.resolve(instance, type: String(cString: type), domain: String(cString: domain), interface: interface)
            } else {
                discovery.lost(instance)
            }
        }, context)
        guard error == kDNSServiceErr_NoError, let ref else { return }
        DNSServiceSetDispatchQueue(ref, queue)
        browser = ref
    }

    private func resolve(_ instance: String, type: String, domain: String, interface: UInt32) {
        guard resolving[instance] == nil else { return }
        let context = Unmanaged.passRetained(Resolve(self, instance))
        var ref: DNSServiceRef?
        let error = DNSServiceResolve(&ref, 0, interface, instance, type, domain, { _, _, _, error, _, host, port, length, txt, context in
            guard let context else { return }
            let resolve = Unmanaged<Resolve>.fromOpaque(context).takeUnretainedValue()
            guard error == kDNSServiceErr_NoError, let host, let txt else { return }
            func value(_ key: String) -> String? {
                var size: UInt8 = 0
                guard let pointer = TXTRecordGetValuePtr(length, txt, key, &size) else { return nil }
                return String(decoding: UnsafeRawBufferPointer(start: pointer, count: Int(size)), as: UTF8.self)
            }
            resolve.discovery?.resolved(resolve.instance, fingerprint: value("id") ?? "", name: value("name") ?? "",
                                        host: String(cString: host), port: UInt16(bigEndian: port))
        }, context.toOpaque())
        guard error == kDNSServiceErr_NoError, let ref else {
            context.release()
            return
        }
        DNSServiceSetDispatchQueue(ref, queue)
        resolving[instance] = (ref, context)
    }

    private func resolved(_ instance: String, fingerprint: String, name: String, host: String, port: UInt16) {
        if let entry = resolving.removeValue(forKey: instance) {
            DNSServiceRefDeallocate(entry.ref)
            entry.context.release()
        }
        // Ourselves, or a record too broken to connect to.
        guard fingerprint.count == 64, fingerprint != ownFingerprint, port != 0 else { return }
        lock.withLock { found[instance] = Found(fingerprint: fingerprint, name: String(name.prefix(64)), host: host, port: port) }
        onChange?()
    }

    private func lost(_ instance: String) {
        if let entry = resolving.removeValue(forKey: instance) {
            DNSServiceRefDeallocate(entry.ref)
            entry.context.release()
        }
        let removed = lock.withLock { found.removeValue(forKey: instance) != nil }
        if removed { onChange?() }
    }
}
