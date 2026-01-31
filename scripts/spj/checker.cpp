#include "testlib.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <fstream>
#include <map>
#include <string>
#include <vector>

struct SpjConfig {
    bool ignoreOrder = false;
    std::string compareUnit = "TOKEN";
    bool hasFloatEps = false;
    double floatEps = 0.0;
};

static std::string trimLocal(const std::string &value) {
    size_t start = 0;
    while (start < value.size() && std::isspace(static_cast<unsigned char>(value[start]))) {
        start++;
    }
    size_t end = value.size();
    while (end > start && std::isspace(static_cast<unsigned char>(value[end - 1]))) {
        end--;
    }
    return value.substr(start, end - start);
}

static std::string toUpper(const std::string &value) {
    std::string result = value;
    for (size_t i = 0; i < result.size(); i++) {
        result[i] = static_cast<char>(std::toupper(static_cast<unsigned char>(result[i])));
    }
    return result;
}

static SpjConfig loadConfig(const char *path) {
    SpjConfig config;
    if (path == nullptr) {
        return config;
    }
    std::ifstream in(path);
    if (!in.is_open()) {
        return config;
    }
    std::string line;
    while (std::getline(in, line)) {
        line = trimLocal(line);
        if (line.empty() || line[0] == '#') {
            continue;
        }
        size_t pos = line.find('=');
        if (pos == std::string::npos) {
            continue;
        }
        std::string key = trimLocal(line.substr(0, pos));
        std::string value = trimLocal(line.substr(pos + 1));
        if (key == "ignoreOrder") {
            config.ignoreOrder = (value == "true" || value == "1" || value == "TRUE");
        } else if (key == "compareUnit") {
            config.compareUnit = toUpper(value);
        } else if (key == "floatEps") {
            if (!value.empty()) {
                config.hasFloatEps = true;
                config.floatEps = std::stod(value);
            }
        }
    }
    return config;
}

static std::vector<std::string> readAllTokens(InStream &stream) {
    std::vector<std::string> tokens;
    while (!stream.seekEof()) {
        tokens.push_back(stream.readToken());
    }
    return tokens;
}

static std::vector<std::string> readAllLines(InStream &stream) {
    std::vector<std::string> lines;
    while (!stream.seekEof()) {
        lines.push_back(stream.readLine());
    }
    return lines;
}

static void compareIgnoreOrderTokens() {
    std::vector<std::string> expected = readAllTokens(ans);
    std::vector<std::string> actual = readAllTokens(ouf);
    std::sort(expected.begin(), expected.end());
    std::sort(actual.begin(), actual.end());
    if (expected != actual) {
        quitf(_wa, "unordered token mismatch");
    }
    quitf(_ok, "OK");
}

static void compareIgnoreOrderLines() {
    std::vector<std::string> expected = readAllLines(ans);
    std::vector<std::string> actual = readAllLines(ouf);
    std::sort(expected.begin(), expected.end());
    std::sort(actual.begin(), actual.end());
    if (expected != actual) {
        quitf(_wa, "unordered line mismatch");
    }
    quitf(_ok, "OK");
}

static bool floatEqual(double expected, double actual, double eps) {
    double diff = fabs(expected - actual);
    double scale = std::max(1.0, std::max(fabs(expected), fabs(actual)));
    double tol = eps * scale;
    return diff <= tol + 1e-12;
}

static void compareFloat(double eps) {
    while (!ans.seekEof()) {
        double expected = ans.readDouble();
        double actual = ouf.readDouble();
        if (!floatEqual(expected, actual, eps)) {
            quitf(_wa, "expected=%f found=%f", expected, actual);
        }
    }
    if (!ouf.seekEof()) {
        quitf(_wa, "extra output");
    }
    quitf(_ok, "OK");
}

static void compareTokens() {
    while (!ans.seekEof()) {
        std::string expected = ans.readToken();
        std::string actual = ouf.readToken();
        if (expected != actual) {
            quitf(_wa, "expected=%s found=%s", expected.c_str(), actual.c_str());
        }
    }
    if (!ouf.seekEof()) {
        quitf(_wa, "extra output");
    }
    quitf(_ok, "OK");
}

int main(int argc, char *argv[]) {
    registerTestlibCmd(argc, argv);
    SpjConfig config;
    if (argc >= 5) {
        config = loadConfig(argv[4]);
    }
    if (config.ignoreOrder) {
        if (config.compareUnit == "LINE") {
            compareIgnoreOrderLines();
        } else {
            compareIgnoreOrderTokens();
        }
    }
    if (config.hasFloatEps) {
        compareFloat(config.floatEps);
    }
    compareTokens();
    return 0;
}
