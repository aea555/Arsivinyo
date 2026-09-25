import Foundation

/// A decoded JSON value that can cross an actor boundary.
///
/// The engine's replies differ by operation — a download returns a path and a title, a
/// diagnostics call returns thirty fields — so they arrive as loose JSON. `[String: Any]`
/// would be the obvious way to carry that and is not `Sendable`, which matters here because
/// the reader is a detached task and everything reading it is on the main actor.
public enum JSONValue: Sendable, Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(_ any: Any) {
        switch any {
        case let value as String: self = .string(value)
        case let value as Bool: self = .bool(value)
        case let value as NSNumber:
            // NSNumber does not distinguish a bool from a 0 or 1 by type alone.
            if CFGetTypeID(value) == CFBooleanGetTypeID() {
                self = .bool(value.boolValue)
            } else {
                self = .number(value.doubleValue)
            }
        case let value as [Any]: self = .array(value.map(JSONValue.init))
        case let value as [String: Any]: self = .object(value.mapValues(JSONValue.init))
        default: self = .null
        }
    }

    public subscript(key: String) -> JSONValue? {
        guard case .object(let fields) = self else { return nil }
        return fields[key]
    }

    public var string: String? {
        if case .string(let value) = self { return value }
        return nil
    }
    public var double: Double? {
        if case .number(let value) = self { return value }
        return nil
    }
    public var bool: Bool? {
        if case .bool(let value) = self { return value }
        return nil
    }
    public var isNull: Bool { self == .null }

    public var array: [JSONValue]? {
        if case .array(let values) = self { return values }
        return nil
    }
}
