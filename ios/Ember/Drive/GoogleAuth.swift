import Foundation
import AuthenticationServices
import CryptoKit
import Security
import UIKit
import Observation

/// Signs in to Google (Drive books, read-only, and sync's hidden app folder) with Google's
/// standard sign-in page, and keeps the access fresh.
@MainActor
@Observable
final class GoogleAuth: NSObject, ASWebAuthenticationPresentationContextProviding {

    static let driveReadOnly = "https://www.googleapis.com/auth/drive.readonly"
    static let appData = "https://www.googleapis.com/auth/drive.appdata"

    private(set) var grantedScopes: Set<String>
    var lastError: String?

    @ObservationIgnored private var accessToken: String?
    @ObservationIgnored private var expiresAt = Date.distantPast
    @ObservationIgnored private var session: ASWebAuthenticationSession?

    override init() {
        grantedScopes = Set((UserDefaults.standard.string(forKey: "googleScopes") ?? "").split(separator: " ").map(String.init))
        super.init()
    }

    /// The iOS OAuth client made in Ember's Google Cloud project (Info.plist: GoogleClientID).
    var clientID: String { (Bundle.main.object(forInfoDictionaryKey: "GoogleClientID") as? String)?.trimmingCharacters(in: .whitespaces) ?? "" }
    var isConfigured: Bool { clientID.hasSuffix(".apps.googleusercontent.com") }
    private var redirectScheme: String { clientID.split(separator: ".").reversed().joined(separator: ".") }
    private var redirectURI: String { "\(redirectScheme):/oauth2redirect" }

    func has(_ scope: String) -> Bool { grantedScopes.contains(scope) && Keychain.get("googleRefresh") != nil }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first ?? ASPresentationAnchor()
        }
    }

    /// Shows Google's sign-in page and asks for `scopes` (plus anything granted before).
    func connect(scopes: Set<String>) async -> Bool {
        guard isConfigured else {
            lastError = "Google sign-in isn't set up in this build yet."
            return false
        }
        let verifier = Self.randomString(64)
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64URL
        let want = grantedScopes.union(scopes)
        var c = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        c.queryItems = [
            .init(name: "client_id", value: clientID),
            .init(name: "redirect_uri", value: redirectURI),
            .init(name: "response_type", value: "code"),
            .init(name: "scope", value: want.sorted().joined(separator: " ")),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
            .init(name: "access_type", value: "offline"),
            .init(name: "prompt", value: "consent"),
            .init(name: "include_granted_scopes", value: "true"),
        ]
        guard let url = c.url else { return false }
        let callback: URL? = await withCheckedContinuation { cont in
            let s = ASWebAuthenticationSession(url: url, callback: .customScheme(redirectScheme)) { result, _ in
                cont.resume(returning: result)
            }
            s.presentationContextProvider = self
            s.prefersEphemeralWebBrowserSession = false
            self.session = s
            s.start()
        }
        guard let callback, let code = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "code" })?.value else {
            lastError = "Sign-in was cancelled."
            return false
        }
        let ok = await tokenRequest([
            "code": code, "client_id": clientID, "redirect_uri": redirectURI,
            "grant_type": "authorization_code", "code_verifier": verifier,
        ])
        if ok {
            grantedScopes = want
            UserDefaults.standard.set(want.sorted().joined(separator: " "), forKey: "googleScopes")
            lastError = nil
        }
        return ok
    }

    func disconnect() {
        Keychain.delete("googleRefresh")
        accessToken = nil
        grantedScopes = []
        UserDefaults.standard.removeObject(forKey: "googleScopes")
    }

    /// A fresh access token (renewed quietly), or nil if you need to sign in again.
    func token() async -> String? {
        if let t = accessToken, expiresAt.timeIntervalSinceNow > 120 { return t }
        guard let refresh = Keychain.get("googleRefresh") else { return nil }
        let ok = await tokenRequest(["client_id": clientID, "grant_type": "refresh_token", "refresh_token": refresh])
        return ok ? accessToken : nil
    }

    private func tokenRequest(_ form: [String: String]) async -> Bool {
        var req = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = form.map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? $0.value)" }
            .joined(separator: "&").data(using: .utf8)
        guard let got = try? await URLSession.shared.data(for: req),
              let json = try? JSONSerialization.jsonObject(with: got.0) as? [String: Any] else {
            lastError = "Couldn't reach Google."
            return false
        }
        guard (got.1 as? HTTPURLResponse)?.statusCode == 200, let at = json["access_token"] as? String else {
            lastError = (json["error_description"] as? String) ?? (json["error"] as? String) ?? "Google said no."
            if (json["error"] as? String) == "invalid_grant" { disconnect() }
            return false
        }
        accessToken = at
        expiresAt = Date().addingTimeInterval(Double((json["expires_in"] as? NSNumber)?.intValue ?? 3600))
        if let r = json["refresh_token"] as? String { Keychain.set("googleRefresh", r) }
        return true
    }

    private static func randomString(_ n: Int) -> String {
        let chars = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return String((0..<n).map { _ in chars.randomElement()! })
    }
}

extension Data {
    var base64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

/// Small secrets (the Google refresh token) in the iOS Keychain.
enum Keychain {
    static func set(_ key: String, _ value: String) {
        delete(key)
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key,
                                kSecValueData as String: Data(value.utf8), kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func get(_ key: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key,
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func delete(_ key: String) {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key] as CFDictionary)
    }
}
