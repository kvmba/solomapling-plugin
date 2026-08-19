import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import wzlib.WzImage;
import wzlib.WzImageProperty;
import wzlib.WzMapleVersion;
import wzlib.WzObject;
import wzlib.property.WzCanvasProperty;
import wzlib.property.WzDoubleProperty;
import wzlib.property.WzFloatProperty;
import wzlib.property.WzIntProperty;
import wzlib.property.WzLongProperty;
import wzlib.property.WzShortProperty;
import wzlib.property.WzStringProperty;
import wzlib.property.WzUOLProperty;
import wzlib.property.WzVectorProperty;
import wzlib.util.WzBinaryReader;
import wzlib.util.WzTool;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deep-audit standalone MapleStory Character .img files against server XML.
 *
 * Checks (for each bot-used ID):
 *   1. client .img parses with GMS IV
 *   2. every canvas decodes to a valid BufferedImage
 *   3. every UOL / _inlink resolves inside the same image
 *   4. structural signature tree matches server Character.wz XML
 *
 * Compile / run (from this directory):
 *   javac -cp /path/to/wzlib-java-1.0.0-SNAPSHOT.jar WzDeepAudit.java
 *   java  -cp .:/path/to/wzlib-java-1.0.0-SNAPSHOT.jar WzDeepAudit \
 *         <client Character dir> <server Character.wz dir> <ids.ps1|ids.txt>
 */
public final class WzDeepAudit {
    private static final Pattern IDS_LINE = Pattern.compile("\\$ids\\s*=\\s*@\\(([^)]*)\\)");

    private WzDeepAudit() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: java WzDeepAudit <client Character> <server Character.wz> <ids.ps1|ids.txt>");
            System.exit(2);
        }

        Path clientRoot = Path.of(args[0]);
        Path serverRoot = Path.of(args[1]);
        Set<Integer> ids = readIds(Path.of(args[2]));
        Map<Integer, Path> client = index(clientRoot, ".img");
        Map<Integer, Path> server = index(serverRoot, ".img.xml");

        int checked = 0;
        int parseFailures = 0;
        int treeMismatches = 0;
        int resourceErrors = 0;
        long[] totals = new long[8]; // props, canvas, decoded, uol, uolOk, inlink, inlinkOk, outlink
        List<String> findings = new ArrayList<>();

        long t0 = System.nanoTime();
        for (int id : ids) {
            Path imgPath = client.get(id);
            Path xmlPath = server.get(id);
            if (imgPath == null || xmlPath == null) {
                findings.add(id + "\tMISSING_FILE\tclient=" + imgPath + "\tserver=" + xmlPath);
                resourceErrors++;
                continue;
            }

            try (WzBinaryReader reader = new WzBinaryReader(
                    imgPath.toFile(), WzTool.getIvByMapleVersion(WzMapleVersion.GMS))) {
                WzImage image = new WzImage(imgPath.getFileName().toString(), reader, 0);
                image.setOffset(0);
                image.parseImage();
                if (!image.isParsed() || image.getProperties().isEmpty()) {
                    findings.add(id + "\tPARSE_EMPTY\t" + imgPath);
                    parseFailures++;
                    continue;
                }

                Map<String, String> binaryTree = new LinkedHashMap<>();
                long[] stats = new long[8];
                List<String> errors = new ArrayList<>();
                for (WzImageProperty property : image.getProperties()) {
                    visitBinary(image, property, property.getName(), binaryTree, stats, errors);
                }
                for (int i = 0; i < totals.length; i++) {
                    totals[i] += stats[i];
                }
                if (!errors.isEmpty()) {
                    resourceErrors += errors.size();
                    for (String error : errors) {
                        findings.add(id + "\tRESOURCE_ERROR\t" + error);
                    }
                }

                Map<String, String> xmlTree = readXmlTree(xmlPath);
                Set<String> allPaths = new HashSet<>(binaryTree.keySet());
                allPaths.addAll(xmlTree.keySet());
                int differences = 0;
                List<String> examples = new ArrayList<>();
                for (String path : allPaths) {
                    String actual = binaryTree.get(path);
                    String expected = xmlTree.get(path);
                    if (!Objects.equals(actual, expected)) {
                        differences++;
                        if (examples.size() < 3) {
                            examples.add(path + " [client=" + actual + ", server=" + expected + "]");
                        }
                    }
                }
                if (differences > 0) {
                    treeMismatches++;
                    findings.add(id + "\tTREE_MISMATCH\tcount=" + differences + "\t"
                            + String.join(" | ", examples));
                }
                checked++;
            } catch (Throwable error) {
                parseFailures++;
                findings.add(id + "\tPARSE_FAILURE\t" + error.getClass().getSimpleName()
                        + ": " + error.getMessage() + "\t" + imgPath);
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("IDs requested: " + ids.size());
        System.out.println("Images checked: " + checked);
        System.out.println("Parse failures: " + parseFailures);
        System.out.println("Tree mismatches (client binary vs server XML): " + treeMismatches);
        System.out.println("Resource errors: " + resourceErrors);
        System.out.println("Properties traversed: " + totals[0]);
        System.out.println("Canvas decoded: " + totals[2] + "/" + totals[1]);
        System.out.println("UOL resolved: " + totals[4] + "/" + totals[3]);
        System.out.println("_inlink resolved: " + totals[6] + "/" + totals[5]);
        System.out.println("_outlink references: " + totals[7] + " (reported, not cross-file resolved)");
        System.out.println("Elapsed ms: " + ms);
        System.out.println();
        if (findings.isEmpty()) {
            System.out.println("RESULT: PASS");
        } else {
            System.out.println("RESULT: FINDINGS " + findings.size());
            for (String finding : findings) {
                System.out.println(finding);
            }
        }
        System.exit(findings.isEmpty() ? 0 : 1);
    }

    private static Set<Integer> readIds(Path path) throws Exception {
        String text = Files.readString(path);
        Matcher matcher = IDS_LINE.matcher(text);
        Set<Integer> result = new TreeSet<>();
        if (matcher.find()) {
            for (String value : matcher.group(1).split(",")) {
                result.add(Integer.parseInt(value.trim()));
            }
            return result;
        }
        // fallback: one integer per line / comma-separated
        for (String token : text.split("[,\\s]+")) {
            if (!token.isBlank() && token.chars().allMatch(Character::isDigit)) {
                result.add(Integer.parseInt(token));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No IDs found in " + path);
        }
        return result;
    }

    private static Map<Integer, Path> index(Path root, String suffix) throws Exception {
        Map<Integer, Path> result = new HashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        String base = name.substring(0, name.length() - suffix.length())
                                .replaceFirst("^0+", "");
                        if (base.isEmpty()) {
                            base = "0";
                        }
                        if (base.chars().allMatch(Character::isDigit)) {
                            result.put(Integer.parseInt(base), path);
                        }
                    });
        }
        return result;
    }

    private static void visitBinary(
            WzImage image,
            WzImageProperty property,
            String path,
            Map<String, String> tree,
            long[] stats,
            List<String> errors) {
        stats[0]++;
        tree.put(path, signature(property));

        if (property instanceof WzCanvasProperty canvas) {
            stats[1]++;
            try {
                BufferedImage decoded = canvas.getPngProperty().getImage(false);
                if (decoded == null) {
                    errors.add(path + ": canvas decoded to null");
                } else if (decoded.getWidth() != canvas.getPngProperty().getWidth()
                        || decoded.getHeight() != canvas.getPngProperty().getHeight()) {
                    errors.add(path + ": decoded dimensions "
                            + decoded.getWidth() + "x" + decoded.getHeight()
                            + " != header " + canvas.getPngProperty().getWidth()
                            + "x" + canvas.getPngProperty().getHeight());
                } else {
                    // touch every pixel so lazy raster failures surface
                    long checksum = 0;
                    for (int y = 0; y < decoded.getHeight(); y++) {
                        for (int x = 0; x < decoded.getWidth(); x++) {
                            checksum += decoded.getRGB(x, y);
                        }
                    }
                    if (checksum == Long.MIN_VALUE) {
                        // never happens; keep checksum live for JIT
                        errors.add(path + ": impossible checksum");
                    }
                    stats[2]++;
                }
            } catch (Throwable error) {
                errors.add(path + ": canvas decode failed: "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }

        if (property instanceof WzUOLProperty uol) {
            stats[3]++;
            if (resolveRelative(image, property.getParent(), uol.getUOL()) == null) {
                errors.add(path + ": broken UOL -> " + uol.getUOL());
            } else {
                stats[4]++;
            }
        }

        if (property instanceof WzStringProperty stringProperty) {
            if ("_inlink".equals(property.getName())) {
                stats[5]++;
                if (image.getFromPath(stringProperty.getString()) == null) {
                    errors.add(path + ": broken _inlink -> " + stringProperty.getString());
                } else {
                    stats[6]++;
                }
            } else if ("_outlink".equals(property.getName())) {
                stats[7]++;
            }
        }

        List<WzImageProperty> children = property.getProperties();
        if (children != null) {
            for (WzImageProperty child : children) {
                visitBinary(image, child, path + "/" + child.getName(), tree, stats, errors);
            }
        }
    }

    private static WzObject resolveRelative(WzImage image, WzObject start, String value) {
        WzObject current = value.startsWith("/") ? image : start;
        for (String part : value.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                current = current != null ? current.getParent() : null;
            } else {
                current = current != null ? current.getChild(part) : null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static String signature(WzImageProperty property) {
        if (property instanceof WzCanvasProperty canvas) {
            return "canvas|" + canvas.getPngProperty().getWidth() + "x"
                    + canvas.getPngProperty().getHeight();
        }
        if (property instanceof WzVectorProperty vector) {
            return "vector|" + vector.getX().getInt() + "," + vector.getY().getInt();
        }
        if (property instanceof WzUOLProperty uol) {
            return "uol|" + uol.getUOL();
        }
        if (property instanceof WzStringProperty value) {
            return "string|" + value.getString();
        }
        if (property instanceof WzIntProperty value) {
            return "int|" + value.getInt();
        }
        if (property instanceof WzShortProperty value) {
            return "short|" + value.getShort();
        }
        if (property instanceof WzLongProperty value) {
            return "long|" + value.getLong();
        }
        if (property instanceof WzFloatProperty value) {
            return "float|" + value.getFloat();
        }
        if (property instanceof WzDoubleProperty value) {
            return "double|" + value.getDouble();
        }
        if (property.getProperties() != null) {
            return "imgdir";
        }
        return property.getClass().getSimpleName().toLowerCase();
    }

    private static Map<String, String> readXmlTree(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        Element root = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
        Map<String, String> result = new LinkedHashMap<>();
        visitXmlChildren(root, "", result);
        return result;
    }

    private static void visitXmlChildren(Element parent, String parentPath, Map<String, String> tree) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element) || !element.hasAttribute("name")) {
                continue;
            }
            String path = parentPath.isEmpty()
                    ? element.getAttribute("name")
                    : parentPath + "/" + element.getAttribute("name");
            tree.put(path, xmlSignature(element));
            visitXmlChildren(element, path, tree);
        }
    }

    private static String xmlSignature(Element element) {
        return switch (element.getTagName()) {
            case "canvas" -> "canvas|" + element.getAttribute("width") + "x"
                    + element.getAttribute("height");
            case "vector" -> "vector|" + element.getAttribute("x") + ","
                    + element.getAttribute("y");
            case "uol" -> "uol|" + element.getAttribute("value");
            case "string" -> "string|" + element.getAttribute("value");
            case "int" -> "int|" + element.getAttribute("value");
            case "short" -> "short|" + element.getAttribute("value");
            case "long" -> "long|" + element.getAttribute("value");
            case "float" -> "float|" + Float.parseFloat(element.getAttribute("value"));
            case "double" -> "double|" + Double.parseDouble(element.getAttribute("value"));
            case "imgdir" -> "imgdir";
            default -> element.getTagName().toLowerCase();
        };
    }
}
