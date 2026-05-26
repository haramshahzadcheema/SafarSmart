import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.mindrot.jbcrypt.BCrypt;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

// ═══════════════════════════════════════════════════════════════════════════════
//                              MAIN APPLICATION
// ═══════════════════════════════════════════════════════════════════════════════
public class Main extends Application {

    // ── DB credentials ──────────────────────────────────────────────────────
   private static final String DB_URL = "your_database_url";
private static final String DB_USER = "your_username";
private static final String DB_PASS = "your_password";
    private static Connection conn;
// Configure your local MySQL credentials here before running
    // ── Session state ────────────────────────────────────────────────────────
    private Person  currentUser;
    private Stage   primaryStage;

    // ════════════════════════════════════════════════════════════════════════
    //  SECTION 1 — INTERFACES
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Any object that knows how to compute travel cost for a given Route.
     * Implemented by every Vehicle subclass (OwnCar, RideHailService, PublicTransport).
     */
    interface CostCalculator {
        double calculateCost(Route route);
        String getCostBreakdown(Route route);
    }

    /**
     * Any object that can be searched by a plain-text keyword.
     * Implemented by City, Route, and Suggestion — enabling a generic search bar.
     */
    interface Searchable {
        boolean matches(String keyword);
        String  getDisplayLabel();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SECTION 2 — MODELS
    // ════════════════════════════════════════════════════════════════════════

    // ── City ─────────────────────────────────────────────────────────────────
    static class City implements Searchable {
        final int    cityId;
        final String cityName, province;
        final Double latitude, longitude;

        City(int id, String name, String province) {
            this(id, name, province, null, null);
        }

        City(int id, String name, String province, Double lat, Double lon) {
            this.cityId    = id;
            this.cityName  = name;
            this.province  = province;
            this.latitude  = lat;
            this.longitude = lon;
        }

        @Override
        public boolean matches(String keyword) {
            if (keyword == null || keyword.isBlank()) return false;
            String kl = keyword.toLowerCase();
            return cityName.toLowerCase().contains(kl)
                    || (province != null && province.toLowerCase().contains(kl));
        }

        @Override public String getDisplayLabel() { return cityName + (province != null ? ", " + province : ""); }
        @Override public String toString()        { return cityName; }
    }

    // ── Route ─────────────────────────────────────────────────────────────────
    static class Route implements Searchable {
        final int    routeId;
        final City   origin, destination;
        final double distanceKm, tollFeePkr;
        final int    durationMins;

        Route(int id, City origin, City dest, double distKm, double toll, int durMins) {
            this.routeId      = id;
            this.origin       = origin;
            this.destination  = dest;
            this.distanceKm   = distKm;
            this.tollFeePkr   = toll;
            this.durationMins = durMins;
        }

        String getDurationFormatted() {
            int h = durationMins / 60, m = durationMins % 60;
            if (h == 0) return m + "m";
            if (m == 0) return h + "h";
            return h + "h " + m + "m";
        }

        @Override public boolean matches(String k)  { return origin.matches(k) || destination.matches(k); }
        @Override public String  getDisplayLabel()   { return origin.cityName + " → " + destination.cityName + " (" + distanceKm + " km)"; }
        @Override public String  toString()          { return getDisplayLabel(); }
    }

    // ── FuelPrice ─────────────────────────────────────────────────────────────
    static class FuelPrice {
        enum FuelType { PETROL, DIESEL, LDO, CNG }

        final FuelType fuelType;
        final double   pricePerLitre;

        FuelPrice(FuelType type, double price) { this.fuelType = type; this.pricePerLitre = price; }

        static FuelType parseFuelType(String s) {
            switch (s.toUpperCase().trim()) {
                case "PETROL": return FuelType.PETROL;
                case "DIESEL": return FuelType.DIESEL;
                case "LDO":    return FuelType.LDO;
                case "CNG":    return FuelType.CNG;
                default: throw new IllegalArgumentException("Unknown fuel type: " + s);
            }
        }
    }

    // ── Vehicle (abstract) ────────────────────────────────────────────────────
    /**
     * Abstract base for all transport modes.
     * Implements CostCalculator — every concrete subclass must compute costs.
     * Demonstrates ABSTRACTION and provides an INHERITANCE root.
     */
    static abstract class Vehicle implements CostCalculator {
        final String name, type;

        Vehicle(String name, String type) { this.name = name; this.type = type; }

        /** Override in PublicTransport to use operator-specific duration. */
        int getEstimatedDurationMins(Route route) { return route.durationMins; }

        /** Comfort rating 1–5; used in the "Best Overall" recommendation. */
        abstract int getComfortRating();

        @Override public String toString() { return name; }
    }

    // ── OwnCar ────────────────────────────────────────────────────────────────
    /**
     * User's personal car. Cost = (distance / mileage) × fuel_price + tolls.
     * POLYMORPHISM: calculateCost resolved at runtime when iterating Vehicle list.
     */
    static class OwnCar extends Vehicle {
        final int            vehicleId, userId;
        final FuelPrice.FuelType fuelType;
        final double         mileageKmpl;
        final FuelPrice      fuelPrice;

        OwnCar(int vehicleId, int userId, String name,
               FuelPrice.FuelType fuelType, double mileage, FuelPrice fuelPrice) {
            super(name, "Own Car");
            this.vehicleId   = vehicleId;
            this.userId      = userId;
            this.fuelType    = fuelType;
            this.mileageKmpl = mileage;
            this.fuelPrice   = fuelPrice;
        }

        @Override
        public double calculateCost(Route r) {
            double litres = r.distanceKm / mileageKmpl;
            return Math.round((litres * fuelPrice.pricePerLitre + r.tollFeePkr) * 100.0) / 100.0;
        }

        @Override
        public String getCostBreakdown(Route r) {
            double litres = r.distanceKm / mileageKmpl;
            double fuel   = litres * fuelPrice.pricePerLitre;
            return String.format(
                    "Fuel : %.1f L × PKR %.2f = PKR %.2f%nTolls: PKR %.2f%nTotal: PKR %.2f",
                    litres, fuelPrice.pricePerLitre, fuel, r.tollFeePkr, fuel + r.tollFeePkr
            );
        }

        @Override public int getComfortRating() { return 5; }
    }

    // ── RideHailService ───────────────────────────────────────────────────────
    /**
     * Uber / Careem / InDrive style services.
     * Cost = base_fare + per_km × distance.
     */
    static class RideHailService extends Vehicle {
        final double baseFarePkr, perKmPkr;
        final int    routeId;

        RideHailService(String name, int routeId, double baseFare, double perKm) {
            super(name, "Ride-Hail");
            this.routeId     = routeId;
            this.baseFarePkr = baseFare;
            this.perKmPkr    = perKm;
        }

        @Override
        public double calculateCost(Route r) {
            return Math.round((baseFarePkr + perKmPkr * r.distanceKm) * 100.0) / 100.0;
        }

        @Override
        public String getCostBreakdown(Route r) {
            double dist = perKmPkr * r.distanceKm;
            return String.format(
                    "Base fare   : PKR %.2f%n%.1f km × PKR %.2f/km = PKR %.2f%nTotal       : PKR %.2f",
                    baseFarePkr, r.distanceKm, perKmPkr, dist, baseFarePkr + dist
            );
        }

        @Override public int getComfortRating() { return 4; }
    }

    // ── PublicTransport ───────────────────────────────────────────────────────
    /**
     * Daewoo / Faisal Movers / Coach / Wagon etc.
     * Fixed fare per trip; duration comes from operator schedule, not route estimate.
     */
    static class PublicTransport extends Vehicle {
        final double farePkr;
        final int    durationMins;

        PublicTransport(String name, double fare, int durationMins) {
            super(name, "Public Transport");
            this.farePkr      = fare;
            this.durationMins = durationMins;
        }

        @Override public double calculateCost(Route r) { return farePkr; }

        @Override
        public String getCostBreakdown(Route r) {
            return String.format("%s fare: PKR %.2f%nDuration : %dh %dm",
                    name, farePkr, durationMins / 60, durationMins % 60);
        }

        /** Operator-specific duration, not the route default. */
        @Override public int getEstimatedDurationMins(Route r) { return durationMins; }

        @Override
        public int getComfortRating() {
            String nl = name.toLowerCase();
            if (nl.contains("daewoo") || nl.contains("faisal")) return 4;
            if (nl.contains("natco")  || nl.contains("coach"))  return 3;
            return 2;
        }
    }

    // ── Person (abstract) ─────────────────────────────────────────────────────
    /**
     * Abstract base for all user types.
     * ABSTRACTION: canSaveTrips() hides the "registered vs guest" distinction.
     */
    static abstract class Person {
        final String displayName;
        Person(String name) { this.displayName = name; }
        abstract boolean canSaveTrips();
        abstract String  getAccountLabel();
    }

    // ── RegisteredUser ────────────────────────────────────────────────────────
    static class RegisteredUser extends Person {
        final int            userId;
        final String         username, email, passwordHash;
        final City           homeCity;
        final List<OwnCar>   vehicles = new ArrayList<>();

        RegisteredUser(int id, String username, String email, String hash, City homeCity) {
            super(username);
            this.userId       = id;
            this.username     = username;
            this.email        = email;
            this.passwordHash = hash;
            this.homeCity     = homeCity;
        }

        @Override public boolean canSaveTrips()  { return true; }
        @Override public String  getAccountLabel() { return "Registered"; }
    }

    // ── GuestUser ─────────────────────────────────────────────────────────────
    static class GuestUser extends Person {
        GuestUser() { super("Guest"); }
        @Override public boolean canSaveTrips()   { return false; }
        @Override public String  getAccountLabel() { return "Guest"; }
    }

    // ── Suggestion ────────────────────────────────────────────────────────────
    static class Suggestion implements Searchable {
        final City   city;
        final String name, type, description;

        Suggestion(City city, String name, String type, String description) {
            this.city = city; this.name = name; this.type = type; this.description = description;
        }

        @Override public boolean matches(String k)    { return name.toLowerCase().contains(k.toLowerCase()); }
        @Override public String  getDisplayLabel()    { return name + " (" + type + ")"; }
    }

    // ── CostComparison ────────────────────────────────────────────────────────
    /**
     * Aggregates all transport options for a single Route.
     * Sorting / filtering uses POLYMORPHISM through the Vehicle interface.
     */
    static class CostComparison {
        final Route         route;
        final List<Option>  options = new ArrayList<>();

        CostComparison(Route route) { this.route = route; }

        void addOption(Vehicle v) {
            options.add(new Option(v, v.calculateCost(route), v.getEstimatedDurationMins(route)));
        }

        Option getCheapest() {
            return options.stream().min(Comparator.comparingDouble(o -> o.cost)).orElse(null);
        }

        List<Option> sortedByCost() {
            return options.stream()
                    .sorted(Comparator.comparingDouble(o -> o.cost))
                    .collect(Collectors.toList());
        }

        static class Option {
            final Vehicle vehicle;
            final double  cost;
            final int     durationMins;

            Option(Vehicle v, double cost, int durMins) {
                this.vehicle      = v;
                this.cost         = cost;
                this.durationMins = durMins;
            }

            String getDuration() { return (durationMins / 60) + "h " + (durationMins % 60) + "m"; }
        }
    }

    // ── SavedTrip / ComparisonRecord ─────────────────────────────────────────
    static class SavedTrip {
        final int    tripId;
        final String title, notes;
        SavedTrip(int id, String title, String notes) { this.tripId = id; this.title = title; this.notes = notes; }
    }

    static class ComparisonRecord {
        final int       id;
        final String    origin, dest, bestOption;
        final double    ownCar, ridehail, transport;
        final Timestamp when;

        ComparisonRecord(int id, String origin, String dest,
                         double ownCar, double ridehail, double transport,
                         String bestOption, Timestamp when) {
            this.id = id; this.origin = origin; this.dest = dest;
            this.ownCar = ownCar; this.ridehail = ridehail; this.transport = transport;
            this.bestOption = bestOption; this.when = when;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SECTION 3 — DATABASE LAYER
    //  All DB access is encapsulated here. UI never touches SQL directly.
    // ════════════════════════════════════════════════════════════════════════

    private static Connection getConnection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            try { Class.forName("com.mysql.cj.jdbc.Driver"); }
            catch (ClassNotFoundException e) { throw new SQLException("MySQL JDBC driver not found", e); }
            conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
        }
        return conn;
    }

    // ── City queries ──────────────────────────────────────────────────────────
    static List<City> loadAllCities() throws SQLException {
        List<City> list = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT city_id, city_name, province, latitude, longitude FROM cities ORDER BY city_name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next())
                list.add(mapCity(rs, 1));
        }
        return list;
    }

    static City findCityById(int id) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT city_id, city_name, province, latitude, longitude FROM cities WHERE city_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? mapCity(rs, 1) : null; }
        }
    }

    static City findCityByName(String name) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT city_id, city_name, province, latitude, longitude FROM cities WHERE LOWER(city_name) = LOWER(?) LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? mapCity(rs, 1) : null; }
        }
    }

    /** Maps columns [colOffset..colOffset+4] → City object. */
    private static City mapCity(ResultSet rs, int offset) throws SQLException {
        return new City(
                rs.getInt(offset),
                rs.getString(offset + 1),
                rs.getString(offset + 2),
                rs.getObject(offset + 3) != null ? rs.getDouble(offset + 3) : null,
                rs.getObject(offset + 4) != null ? rs.getDouble(offset + 4) : null
        );
    }

    // ── Route queries ─────────────────────────────────────────────────────────
    static Route findRoute(String originName, String destName) throws SQLException {
        String sql =
                "SELECT r.route_id, r.distance_km, r.toll_fee_pkr, r.duration_mins, " +
                        "  c1.city_id, c1.city_name, c1.province, c1.latitude, c1.longitude, " +
                        "  c2.city_id, c2.city_name, c2.province, c2.latitude, c2.longitude  " +
                        "FROM routes r                                                          " +
                        "  JOIN cities c1 ON r.origin_city = c1.city_id                        " +
                        "  JOIN cities c2 ON r.dest_city   = c2.city_id                        " +
                        "WHERE LOWER(c1.city_name) = LOWER(?) AND LOWER(c2.city_name) = LOWER(?) LIMIT 1";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, originName);
            ps.setString(2, destName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                City c1 = new City(rs.getInt(5), rs.getString(6), rs.getString(7),
                        rs.getObject(8) != null ? rs.getDouble(8) : null,
                        rs.getObject(9) != null ? rs.getDouble(9) : null);
                City c2 = new City(rs.getInt(10), rs.getString(11), rs.getString(12),
                        rs.getObject(13) != null ? rs.getDouble(13) : null,
                        rs.getObject(14) != null ? rs.getDouble(14) : null);
                return new Route(rs.getInt(1), c1, c2, rs.getDouble(2), rs.getDouble(3), rs.getInt(4));
            }
        }
    }

    /** Haversine great-circle distance in km. */
    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R  = 6371;
        double dL = Math.toRadians(lat2 - lat1);
        double dO = Math.toRadians(lon2 - lon1);
        double a  = Math.sin(dL / 2) * Math.sin(dL / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dO / 2) * Math.sin(dO / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Tries DB first; if not found, estimates from GPS coordinates with ×1.3 road factor.
     * Returns null only if neither city exists in the DB.
     */
    static Route findRouteOrEstimate(String origin, String dest) throws SQLException {
        Route db = findRoute(origin, dest);
        if (db != null) return db;
        City c1 = findCityByName(origin);
        City c2 = findCityByName(dest);
        if (c1 == null || c2 == null) return null;
        double straight = (c1.latitude != null && c2.latitude != null)
                ? haversineKm(c1.latitude, c1.longitude, c2.latitude, c2.longitude)
                : 200.0;
        double road = Math.round(straight * 1.3);
        return new Route(-1, c1, c2, road, road > 200 ? road * 2 : road, (int) Math.round(road));
    }

    // ── Fare data ─────────────────────────────────────────────────────────────
    static List<RideHailService> getRideHailFares(int routeId) throws SQLException {
        List<RideHailService> list = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT service_name, route_id, base_fare_pkr, per_km_pkr FROM ridehail_fares WHERE route_id = ?")) {
            ps.setInt(1, routeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    list.add(new RideHailService(rs.getString(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4)));
            }
        }
        return list;
    }

    static List<PublicTransport> getTransportFares(int originId, int destId) throws SQLException {
        List<PublicTransport> list = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT service_name, fare_pkr, duration_mins FROM transport_fares WHERE origin_city = ? AND dest_city = ?")) {
            ps.setInt(1, originId);
            ps.setInt(2, destId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    list.add(new PublicTransport(rs.getString(1), rs.getDouble(2), rs.getInt(3)));
            }
        }
        return list;
    }

    /** Surge-adjusted estimates when no DB data exists for a route. */
    static List<RideHailService> estimateRideHailFares(Route r) {
        double s = 1.8; // surge multiplier
        return Arrays.asList(
                new RideHailService("Careem (Estimated)",  -1, 350 * s, 30 * s),
                new RideHailService("Yango (Estimated)",   -1, 320 * s, 28 * s),
                new RideHailService("InDrive (Estimated)", -1, 280 * s, 25 * s)
        );
    }

    /** Distance-scaled estimates when no DB data exists for a route. */
    static List<PublicTransport> estimateTransportFares(Route r) {
        double d = r.distanceKm;
        int    m = r.durationMins;
        List<PublicTransport> list = new ArrayList<>(Arrays.asList(
                new PublicTransport("Daewoo Express (Est.)", Math.round(d * 8.0), (int)(m * 1.15)),
                new PublicTransport("Faisal Movers (Est.)",  Math.round(d * 7.5), (int)(m * 1.20)),
                new PublicTransport("Coach (Est.)",          Math.round(d * 5.0), (int)(m * 1.40))
        ));
        if (d < 200) list.add(new PublicTransport("Van/Wagon (Est.)", Math.round(d * 4.5), (int)(m * 1.50)));
        return list;
    }

    // ── Fuel prices (April 2026 OGRA rates) ──────────────────────────────────
    static FuelPrice getLatestFuelPrice(FuelPrice.FuelType type) {
        switch (type) {
            case PETROL: return new FuelPrice(type, 378.41);
            case DIESEL: return new FuelPrice(type, 353.43);
            case LDO:    return new FuelPrice(type, 395.03);
            case CNG:    return new FuelPrice(type, 380.00);
            default:     return null;
        }
    }

    static List<FuelPrice> getAllFuelPrices() {
        return Arrays.asList(
                new FuelPrice(FuelPrice.FuelType.PETROL, 378.41),
                new FuelPrice(FuelPrice.FuelType.DIESEL, 353.43),
                new FuelPrice(FuelPrice.FuelType.LDO,    395.03),
                new FuelPrice(FuelPrice.FuelType.CNG,    380.00)
        );
    }

    // ── Vehicle (user's cars) ─────────────────────────────────────────────────
    static List<OwnCar> loadUserVehicles(int userId) throws SQLException {
        List<OwnCar> list = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT vehicle_id, user_id, vehicle_name, fuel_type, mileage_kmpl FROM vehicles WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    FuelPrice.FuelType ft = FuelPrice.parseFuelType(rs.getString(4));
                    FuelPrice          fp = getLatestFuelPrice(ft);
                    if (fp != null)
                        list.add(new OwnCar(rs.getInt(1), rs.getInt(2), rs.getString(3), ft, rs.getDouble(5), fp));
                }
            }
        }
        return list;
    }

    static void addVehicle(int userId, String name, String fuelType, double mileage) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "INSERT INTO vehicles(user_id, vehicle_name, fuel_type, mileage_kmpl) VALUES(?,?,?,?)")) {
            ps.setInt(1, userId); ps.setString(2, name); ps.setString(3, fuelType); ps.setDouble(4, mileage);
            ps.executeUpdate();
        }
    }

    static void deleteVehicle(int vehicleId, int userId) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "DELETE FROM vehicles WHERE vehicle_id = ? AND user_id = ?")) {
            ps.setInt(1, vehicleId); ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    // ── Suggestions ───────────────────────────────────────────────────────────
    static List<Suggestion> getSuggestions(int cityId) throws SQLException {
        List<Suggestion> list = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT s.name, s.type, s.description, c.city_id, c.city_name, c.province " +
                        "FROM suggestions s JOIN cities c ON s.city_id = c.city_id WHERE s.city_id = ?")) {
            ps.setInt(1, cityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    list.add(new Suggestion(
                            new City(rs.getInt(4), rs.getString(5), rs.getString(6)),
                            rs.getString(1), rs.getString(2), rs.getString(3)
                    ));
            }
        }
        return list;
    }

    // ── User auth ─────────────────────────────────────────────────────────────
    static RegisteredUser findUserByUsername(String username) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT user_id, username, email, password_hash, city_id FROM users WHERE LOWER(username) = LOWER(?) LIMIT 1")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Integer cityId = rs.getObject(5) != null ? rs.getInt(5) : null;
                City    home   = cityId != null ? findCityById(cityId) : null;
                RegisteredUser user = new RegisteredUser(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), home);
                user.vehicles.addAll(loadUserVehicles(user.userId));
                return user;
            }
        }
    }

    static int createUser(String username, String email, String hash, Integer cityId) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "INSERT INTO users(username, email, password_hash, city_id) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username); ps.setString(2, email); ps.setString(3, hash);
            if (cityId != null) ps.setInt(4, cityId); else ps.setNull(4, Types.INTEGER);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { return k.next() ? k.getInt(1) : -1; }
        }
    }

    // ── Trip plans & comparison history ───────────────────────────────────────
    static int saveTrip(int userId, int routeId, String title, String notes) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "INSERT INTO trip_plans(user_id, route_id, title, notes) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId); ps.setInt(2, routeId); ps.setString(3, title); ps.setString(4, notes);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { return k.next() ? k.getInt(1) : -1; }
        }
    }

    static void saveComparison(int userId, int routeId,
                               double ownCar, double ridehail, double transport,
                               String bestOption) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "INSERT INTO cost_comparisons(user_id, route_id, own_car_cost, ridehail_cost, transport_cost, best_option) " +
                        "VALUES(?,?,?,?,?,?)")) {
            ps.setInt(1, userId); ps.setInt(2, routeId);
            ps.setDouble(3, ownCar); ps.setDouble(4, ridehail); ps.setDouble(5, transport);
            ps.setString(6, bestOption);
            ps.executeUpdate();
        }
    }

    static List<SavedTrip> getSavedTrips(int userId) throws SQLException {
        List<SavedTrip> list = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT trip_id, title, notes FROM trip_plans WHERE user_id = ? ORDER BY trip_id DESC")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    list.add(new SavedTrip(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
        }
        return list;
    }

    static List<ComparisonRecord> getComparisonHistory(int userId) throws SQLException {
        List<ComparisonRecord> list = new ArrayList<>();
        String sql =
                "SELECT cc.comparison_id, c1.city_name, c2.city_name, " +
                        "  cc.own_car_cost, cc.ridehail_cost, cc.transport_cost, cc.best_option, cc.compared_at " +
                        "FROM cost_comparisons cc " +
                        "  JOIN routes r  ON cc.route_id   = r.route_id " +
                        "  JOIN cities c1 ON r.origin_city = c1.city_id " +
                        "  JOIN cities c2 ON r.dest_city   = c2.city_id " +
                        "WHERE cc.user_id = ? ORDER BY cc.compared_at DESC";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    list.add(new ComparisonRecord(
                            rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getDouble(4), rs.getDouble(5), rs.getDouble(6),
                            rs.getString(7), rs.getTimestamp(8)
                    ));
            }
        }
        return list;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SECTION 4 — SERVICE LAYER
    //  Business logic separated from both DB and UI.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Builds a full CostComparison for a given route.
     * POLYMORPHISM: every Vehicle subtype is added to the same options list
     *               and compared via the common CostCalculator interface.
     *
     * @param origin       city name (free text)
     * @param dest         city name (free text)
     * @param userVehicles list of the logged-in user's own cars (may be null/empty)
     * @param customMil    mileage for the "Custom" option (km/L)
     * @param fuelTypeStr  "PETROL" / "DIESEL" / "CNG" / "LDO"
     */
    static CostComparison compareRoute(String origin, String dest,
                                       List<OwnCar> userVehicles,
                                       double customMil, String fuelTypeStr) throws SQLException {
        Route route = findRouteOrEstimate(origin, dest);
        if (route == null) return null;

        CostComparison cmp = new CostComparison(route);

        // Add registered user's vehicles
        if (userVehicles != null)
            for (OwnCar car : userVehicles) cmp.addOption(car);

        // Feature 6 — custom fuel type + mileage option
        FuelPrice.FuelType selFuel = FuelPrice.FuelType.PETROL;
        try { selFuel = FuelPrice.parseFuelType(fuelTypeStr); } catch (Exception ignored) {}
        FuelPrice fp = getLatestFuelPrice(selFuel);
        if (fp != null)
            cmp.addOption(new OwnCar(-1, -1, "Custom " + selFuel + " (" + customMil + " km/L)",
                    selFuel, customMil, fp));

        // Ride-hail fares (DB first, then estimates)
        List<RideHailService> rh = (route.routeId > 0)
                ? getRideHailFares(route.routeId) : new ArrayList<>();
        if (rh.isEmpty()) rh = estimateRideHailFares(route);
        for (RideHailService s : rh) cmp.addOption(s);

        // Public transport fares (DB first, then estimates)
        List<PublicTransport> pt = getTransportFares(route.origin.cityId, route.destination.cityId);
        if (pt.isEmpty()) pt = estimateTransportFares(route);
        for (PublicTransport t : pt) cmp.addOption(t);

        return cmp;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SECTION 5 — UI LAYER  (JavaFX)
    // ════════════════════════════════════════════════════════════════════════

    // ── Design tokens ────────────────────────────────────────────────────────
    private static final String C_DARK_GREEN  = "#1B5E20";
    private static final String C_GREEN       = "#4CAF50";
    private static final String C_LIGHT_GREEN = "#81C784";
    private static final String C_BG          = "#121212";
    private static final String C_CARD        = "#1E1E1E";
    private static final String C_WHITE       = "#FFFFFF";
    private static final String C_GREY        = "#B0B0B0";

    private static final String S_PAGE   = "-fx-background-color:" + C_BG + ";";
    private static final String S_CARD   = "-fx-background-color:" + C_CARD + ";-fx-background-radius:12;-fx-padding:24;";
    private static final String S_BTN    = "-fx-background-color:" + C_GREEN + ";-fx-text-fill:white;-fx-font-size:14;"
            + "-fx-font-weight:bold;-fx-padding:10 28;-fx-background-radius:8;-fx-cursor:hand;";
    private static final String S_BTN_O  = "-fx-background-color:transparent;-fx-text-fill:" + C_LIGHT_GREEN + ";"
            + "-fx-font-size:13;-fx-border-color:" + C_LIGHT_GREEN + ";"
            + "-fx-border-radius:8;-fx-background-radius:8;-fx-padding:8 20;-fx-cursor:hand;";
    private static final String S_FIELD  = "-fx-background-color:#2A2A2A;-fx-text-fill:white;-fx-font-size:14;"
            + "-fx-padding:10;-fx-background-radius:8;-fx-border-color:#444;-fx-border-radius:8;";
    private static final String S_LABEL  = "-fx-text-fill:" + C_GREY + ";-fx-font-size:13;";
    private static final String S_COMBO  = "-fx-background-color:#2A2A2A;-fx-font-size:14;-fx-background-radius:8;";

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Label mkTitle(String t)    { Label l = new Label(t); l.setStyle("-fx-text-fill:" + C_WHITE + ";-fx-font-size:28;-fx-font-weight:bold;"); return l; }
    private Label mkSubtitle(String t) { Label l = new Label(t); l.setStyle("-fx-text-fill:" + C_GREY  + ";-fx-font-size:14;"); return l; }
    private Label mkHeading(String t)  { Label l = new Label(t); l.setStyle("-fx-text-fill:" + C_LIGHT_GREEN + ";-fx-font-size:18;-fx-font-weight:bold;"); return l; }
    private Label mkSmall(String t)    { Label l = new Label(t); l.setStyle("-fx-text-fill:" + C_GREY  + ";-fx-font-size:12;"); return l; }

    private TextField   mkField(String prompt) { TextField f = new TextField(); f.setPromptText(prompt); f.setStyle(S_FIELD); f.setPrefWidth(600); return f; }
    private PasswordField mkPass(String prompt) { PasswordField f = new PasswordField(); f.setPromptText(prompt); f.setStyle(S_FIELD); f.setPrefWidth(600); return f; }
    private Label mkErrorLabel()                { Label l = new Label(); l.setStyle("-fx-text-fill:#EF5350;-fx-font-size:12;"); return l; }

    private void alert(String msg)   { new Alert(Alert.AlertType.ERROR,       msg, ButtonType.OK).showAndWait(); }
    private void inform(String msg)  { new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK).showAndWait(); }

    // ════════════════════════════════════════════════════════════════════════
    //  LOGIN SCENE
    // ════════════════════════════════════════════════════════════════════════
    private Scene buildLoginScene() {
        VBox card = new VBox(16);
        card.setStyle(S_CARD);
        card.setMaxWidth(480);
        card.setAlignment(Pos.CENTER);

        Label logo = new Label("SafarSmart");
        logo.setStyle("-fx-text-fill:" + C_GREEN + ";-fx-font-size:36;-fx-font-weight:bold;");

        TextField     userField = mkField("Username");
        PasswordField passField = mkPass("Password");
        Label         errLabel  = mkErrorLabel();

        Button loginBtn  = new Button("Login");             loginBtn.setStyle(S_BTN);   loginBtn.setMaxWidth(340);
        Button signupBtn = new Button("Create Account");    signupBtn.setStyle(S_BTN_O); signupBtn.setMaxWidth(340);
        Button guestBtn  = new Button("Continue as Guest"); guestBtn.setStyle(S_BTN_O);  guestBtn.setMaxWidth(340);

        loginBtn.setOnAction(e -> {
            String u = userField.getText().trim(), p = passField.getText();
            if (u.isEmpty() || p.isEmpty()) { errLabel.setText("Please fill in both fields."); return; }
            try {
                RegisteredUser user = findUserByUsername(u);
                if (user == null)                         { errLabel.setText("No account found with that username."); return; }
                if (!BCrypt.checkpw(p, user.passwordHash)){ errLabel.setText("Incorrect password."); return; }
                currentUser = user;
                primaryStage.setScene(buildDashboardScene());
            } catch (Exception ex) { errLabel.setText("Error: " + ex.getMessage()); }
        });

        signupBtn.setOnAction(e -> primaryStage.setScene(buildSignupScene()));
        guestBtn.setOnAction(e  -> { currentUser = new GuestUser(); primaryStage.setScene(buildDashboardScene()); });

        card.getChildren().addAll(
                logo, mkSubtitle("Pakistan Travel Intelligence Platform"),
                new Separator(), userField, passField, errLabel,
                loginBtn, signupBtn, guestBtn
        );

        StackPane root = new StackPane(card);
        root.setStyle(S_PAGE);
        return new Scene(root, 960, 720);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SIGN-UP SCENE
    // ════════════════════════════════════════════════════════════════════════
    private Scene buildSignupScene() {
        VBox card = new VBox(14);
        card.setStyle(S_CARD);
        card.setMaxWidth(420);
        card.setAlignment(Pos.CENTER);

        TextField     userField  = mkField("Username (3–30 characters)");
        TextField     emailField = mkField("Email address");
        PasswordField passField  = mkPass("Password (minimum 6 characters)");
        Label         errLabel   = mkErrorLabel();

        Button createBtn = new Button("Create Account"); createBtn.setStyle(S_BTN);   createBtn.setMaxWidth(300);
        Button backBtn   = new Button("Back to Login");  backBtn.setStyle(S_BTN_O);    backBtn.setMaxWidth(300);

        createBtn.setOnAction(e -> {
            String u = userField.getText().trim(), em = emailField.getText().trim(), p = passField.getText();
            if (u.length() < 3)       { errLabel.setText("Username must be at least 3 characters."); return; }
            if (!em.contains("@"))    { errLabel.setText("Please enter a valid email address."); return; }
            if (p.length() < 6)       { errLabel.setText("Password must be at least 6 characters."); return; }
            try {
                if (findUserByUsername(u) != null) { errLabel.setText("Username is already taken."); return; }
                int newId = createUser(u, em, BCrypt.hashpw(p, BCrypt.gensalt(12)), null);
                if (newId > 0) { currentUser = findUserByUsername(u); primaryStage.setScene(buildDashboardScene()); }
                else errLabel.setText("Account creation failed. Please try again.");
            } catch (Exception ex) { errLabel.setText("Error: " + ex.getMessage()); }
        });

        backBtn.setOnAction(e -> primaryStage.setScene(buildLoginScene()));

        card.getChildren().addAll(
                mkTitle("Create Account"), mkSubtitle("Join SafarSmart — it's free"),
                new Separator(), userField, emailField, passField, errLabel,
                createBtn, backBtn
        );

        StackPane root = new StackPane(card);
        root.setStyle(S_PAGE);
        return new Scene(root, 960, 720);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DASHBOARD SCENE
    // ════════════════════════════════════════════════════════════════════════
    private Scene buildDashboardScene() {
        // ── Top bar ───────────────────────────────────────────────────────
        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label logo = new Label("SafarSmart");
        logo.setStyle("-fx-text-fill:" + C_GREEN + ";-fx-font-size:22;-fx-font-weight:bold;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label userLabel = new Label(currentUser.displayName + " (" + currentUser.getAccountLabel() + ")");
        userLabel.setStyle("-fx-text-fill:" + C_GREY + ";-fx-font-size:13;");

        Button logoutBtn = new Button("Logout");
        logoutBtn.setStyle(S_BTN_O);
        logoutBtn.setOnAction(e -> { currentUser = null; primaryStage.setScene(buildLoginScene()); });

        topBar.getChildren().addAll(logo, sp, userLabel);

        if (currentUser.canSaveTrips()) {
            Button tripsBtn   = new Button("My Trips");          tripsBtn.setStyle(S_BTN_O);
            Button vehicleBtn = new Button("My Vehicles");       vehicleBtn.setStyle(S_BTN_O);
            Button historyBtn = new Button("History & Charts");  historyBtn.setStyle(S_BTN_O);
            tripsBtn.setOnAction(e   -> showTripsDialog());
            vehicleBtn.setOnAction(e -> showVehiclesDialog());
            historyBtn.setOnAction(e -> showHistoryDashboard());   // Feature 3+4
            topBar.getChildren().addAll(tripsBtn, vehicleBtn, historyBtn);
        }
        topBar.getChildren().add(logoutBtn);

        // ── Fuel prices card ─────────────────────────────────────────────
        VBox fuelCard = new VBox(8);
        fuelCard.setStyle(S_CARD);
        fuelCard.getChildren().add(mkHeading("Current Fuel Prices — April 2026 (OGRA)"));
        for (FuelPrice fp : getAllFuelPrices()) {
            String unit = fp.fuelType == FuelPrice.FuelType.CNG ? "kg" : "litre";
            Label l = new Label("  " + fp.fuelType + " :  PKR " + String.format("%.2f", fp.pricePerLitre) + " / " + unit);
            l.setStyle("-fx-text-fill:" + C_WHITE + ";-fx-font-size:14;");
            fuelCard.getChildren().add(l);
        }

        // ── Search / compare card ─────────────────────────────────────────
        VBox searchCard = new VBox(14);
        searchCard.setStyle(S_CARD);
        searchCard.getChildren().add(mkHeading("Compare Travel Costs"));

        // City dropdowns with live filter
        List<City> allCities = new ArrayList<>();
        try { allCities = loadAllCities(); } catch (Exception ignored) {}
        final List<City> cities = allCities;

        ComboBox<String> originBox = new ComboBox<>();
        ComboBox<String> destBox   = new ComboBox<>();
        cities.forEach(c -> { originBox.getItems().add(c.cityName); destBox.getItems().add(c.cityName); });
        originBox.setEditable(true); destBox.setEditable(true);
        originBox.setPromptText("From..."); destBox.setPromptText("To...");
        originBox.setStyle(S_COMBO); destBox.setStyle(S_COMBO);
        originBox.setPrefWidth(240);  destBox.setPrefWidth(240);

        // Live-filter helper
        setupCityFilter(originBox, cities);
        setupCityFilter(destBox,   cities);

        // Feature 6 — fuel type selector
        ComboBox<String> fuelSel = new ComboBox<>();
        fuelSel.getItems().addAll("PETROL", "DIESEL", "CNG", "LDO");
        fuelSel.setValue("PETROL");
        fuelSel.setStyle(S_COMBO);
        fuelSel.setPrefWidth(100);

        TextField     mileageField  = new TextField("14.0"); mileageField.setPrefWidth(60); mileageField.setStyle(S_FIELD);
        Spinner<Integer> paxSpinner = new Spinner<>(1, 20, 1); paxSpinner.setPrefWidth(70); paxSpinner.setStyle(S_COMBO);
        Spinner<Integer> capSpinner = new Spinner<>(1, 12, 4); capSpinner.setPrefWidth(70); capSpinner.setStyle(S_COMBO);

        Button compareBtn = new Button("Compare"); compareBtn.setStyle(S_BTN);

        Label fromLbl = new Label("From:");   fromLbl.setStyle(S_LABEL);
        Label toLbl   = new Label("To:");     toLbl.setStyle(S_LABEL);
        Label paxLbl  = new Label("Pax:");    paxLbl.setStyle(S_LABEL);
        Label capLbl  = new Label("Car Cap:"); capLbl.setStyle(S_LABEL);
        Label fuelLbl = new Label("Fuel:");   fuelLbl.setStyle(S_LABEL);
        Label milLbl  = new Label("km/L:");   milLbl.setStyle(S_LABEL);

        HBox row1 = new HBox(10, fromLbl, originBox, toLbl, destBox);
        row1.setAlignment(Pos.CENTER_LEFT);
        HBox row2 = new HBox(8, paxLbl, paxSpinner, capLbl, capSpinner, fuelLbl, fuelSel, milLbl, mileageField, compareBtn);
        row2.setAlignment(Pos.CENTER_LEFT);
        searchCard.getChildren().addAll(row1, row2);

        // ── Results area ──────────────────────────────────────────────────
        VBox resultsBox = new VBox(16);

        compareBtn.setOnAction(e -> {
            resultsBox.getChildren().clear();
            String from = originBox.getEditor().getText().trim();
            String to   = destBox.getEditor().getText().trim();
            if (from.isEmpty() || to.isEmpty()) { alert("Please select both origin and destination."); return; }
            if (from.equalsIgnoreCase(to))       { alert("Origin and destination cannot be the same city."); return; }
            try {
                double mileage = 14.0;
                try { mileage = Double.parseDouble(mileageField.getText().trim()); } catch (Exception ignored) {}
                List<OwnCar> userVehicles = (currentUser instanceof RegisteredUser)
                        ? ((RegisteredUser) currentUser).vehicles : null;
                CostComparison cmp = compareRoute(from, to, userVehicles, mileage, fuelSel.getValue());
                if (cmp == null || cmp.options.isEmpty()) { alert("No route or fare data found for this journey."); return; }
                buildResultsUI(resultsBox, cmp, paxSpinner.getValue(), capSpinner.getValue());
            } catch (Exception ex) { alert("Error: " + ex.getMessage()); }
        });

        // ── Final page layout ─────────────────────────────────────────────
        VBox page = new VBox(20,
                topBar,
                mkTitle("Welcome, " + currentUser.displayName + "!"),
                mkSubtitle("Instantly compare travel costs across any Pakistan route."),
                fuelCard, searchCard, resultsBox
        );
        page.setPadding(new Insets(30));
        page.setStyle(S_PAGE);

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background:" + C_BG + ";-fx-background-color:" + C_BG + ";");
        return new Scene(scroll, 960, 720);
    }

    /** Attaches a live-search text listener to a city ComboBox. */
    private void setupCityFilter(ComboBox<String> box, List<City> cities) {
        box.getEditor().textProperty().addListener((obs, old, val) -> {
            if (val == null || val.isBlank()) {
                box.getItems().setAll(cities.stream().map(c -> c.cityName).collect(Collectors.toList()));
                return;
            }
            List<String> filtered = cities.stream()
                    .filter(c -> c.cityName.toLowerCase().contains(val.toLowerCase()))
                    .map(c -> c.cityName).collect(Collectors.toList());
            box.getItems().setAll(filtered);
            if (!filtered.isEmpty()) box.show();
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    //  FEATURE 3 + 4 — History Dashboard (bar chart + pie chart + table)
    // ════════════════════════════════════════════════════════════════════════
    private void showHistoryDashboard() {
        Stage stage = new Stage();
        stage.setTitle("SafarSmart — Comparison History & Charts");

        VBox content = new VBox(16);
        content.setStyle(S_CARD);
        content.setPadding(new Insets(20));
        content.getChildren().add(mkHeading("Your Comparison History"));

        try {
            List<ComparisonRecord> records = getComparisonHistory(((RegisteredUser) currentUser).userId);

            if (records.isEmpty()) {
                content.getChildren().add(mkSubtitle("No comparisons saved yet. Run a comparison and save it first."));
            } else {
                // ── Table (Feature 4) ─────────────────────────────────────
                TableView<ComparisonRecord> table = new TableView<>();
                addCol(table, "Route",     200, r -> r.origin + " → " + r.dest);
                addCol(table, "Own Car",   110, r -> "PKR " + String.format("%,.0f", r.ownCar));
                addCol(table, "Ride-Hail", 110, r -> "PKR " + String.format("%,.0f", r.ridehail));
                addCol(table, "Transport", 110, r -> "PKR " + String.format("%,.0f", r.transport));
                addCol(table, "Best",      130, r -> r.bestOption);
                addCol(table, "Date",      150, r -> r.when != null ? r.when.toString().substring(0, 16) : "N/A");
                table.getItems().addAll(records);
                table.setPrefHeight(Math.min(50 + records.size() * 35, 220));
                content.getChildren().add(table);

                // ── Bar chart — recent comparisons ─────────────────────────
                CategoryAxis  xa  = new CategoryAxis();
                NumberAxis    ya  = new NumberAxis();
                BarChart<String, Number> bar = new BarChart<>(xa, ya);
                bar.setTitle("Cost Comparison History (up to 8 recent routes)");
                bar.setPrefHeight(300);

                XYChart.Series<String, Number> ownSeries  = new XYChart.Series<>(); ownSeries.setName("Own Car");
                XYChart.Series<String, Number> rhSeries   = new XYChart.Series<>(); rhSeries.setName("Ride-Hail");
                XYChart.Series<String, Number> ptSeries   = new XYChart.Series<>(); ptSeries.setName("Transport");

                int limit = Math.min(records.size(), 8);
                for (int i = 0; i < limit; i++) {
                    ComparisonRecord cr = records.get(i);
                    String lbl = abbrev(cr.origin) + "→" + abbrev(cr.dest);
                    ownSeries.getData().add(new XYChart.Data<>(lbl, cr.ownCar));
                    rhSeries.getData().add(new XYChart.Data<>(lbl,  cr.ridehail));
                    ptSeries.getData().add(new XYChart.Data<>(lbl,  cr.transport));
                }
                bar.getData().addAll(ownSeries, rhSeries, ptSeries);
                content.getChildren().add(bar);

                // ── Pie chart — best-option frequency ─────────────────────
                Map<String, Long> freq = records.stream()
                        .collect(Collectors.groupingBy(r -> r.bestOption, Collectors.counting()));
                ObservableList<PieChart.Data> pieData = FXCollections.observableArrayList();
                freq.forEach((k, v) -> pieData.add(new PieChart.Data(k + " (" + v + "×)", v)));
                PieChart pie = new PieChart(pieData);
                pie.setTitle("Which option wins most often?");
                pie.setPrefHeight(260);
                content.getChildren().add(pie);
            }
        } catch (Exception ex) {
            content.getChildren().add(new Label("Error loading history: " + ex.getMessage()));
        }

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background:" + C_BG + ";-fx-background-color:" + C_BG + ";");
        stage.setScene(new Scene(sp, 780, 720));
        stage.show();
    }

    private String abbrev(String s) { return s.substring(0, Math.min(4, s.length())); }

    private <T> void addCol(TableView<T> tv, String title, double w, java.util.function.Function<T, String> fn) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new SimpleStringProperty(fn.apply(c.getValue())));
        col.setPrefWidth(w);
        tv.getColumns().add(col);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SAVED TRIPS DIALOG
    // ════════════════════════════════════════════════════════════════════════
    private void showTripsDialog() {
        Stage stage = new Stage();
        stage.setTitle("My Saved Trips");

        VBox content = new VBox(14);
        content.setStyle(S_CARD);
        content.setPadding(new Insets(20));
        content.getChildren().add(mkHeading("Saved Trips"));

        try {
            List<SavedTrip> trips = getSavedTrips(((RegisteredUser) currentUser).userId);
            if (trips.isEmpty()) {
                content.getChildren().add(mkSubtitle("No trips saved yet."));
            } else {
                TableView<SavedTrip> table = new TableView<>();
                addCol(table, "Title", 200, t -> t.title);
                addCol(table, "Notes", 300, t -> t.notes);
                table.getItems().addAll(trips);
                table.setPrefHeight(Math.min(60 + trips.size() * 35, 320));
                content.getChildren().add(table);
            }
        } catch (Exception ex) {
            content.getChildren().add(new Label("Error: " + ex.getMessage()));
        }

        StackPane root = new StackPane(content);
        root.setStyle(S_PAGE);
        root.setPadding(new Insets(20));
        stage.setScene(new Scene(root, 540, 420));
        stage.show();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  FEATURE 5 — Vehicles CRUD dialog
    // ════════════════════════════════════════════════════════════════════════
    private void showVehiclesDialog() {
        Stage stage = new Stage();
        stage.setTitle("My Vehicles");

        VBox content = new VBox(14);
        content.setStyle(S_CARD);
        content.setPadding(new Insets(20));

        RegisteredUser user = (RegisteredUser) currentUser;

        Runnable refresh = () -> {
            content.getChildren().clear();
            content.getChildren().add(mkHeading("My Vehicles"));

            if (user.vehicles.isEmpty()) {
                content.getChildren().add(mkSubtitle("No vehicles added yet."));
            } else {
                for (OwnCar v : user.vehicles) {
                    HBox row = new HBox(12);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setStyle("-fx-background-color:#252525;-fx-padding:10;-fx-background-radius:8;");

                    Label info = new Label(v.name + "  |  " + v.fuelType + "  |  " + v.mileageKmpl + " km/L");
                    info.setStyle("-fx-text-fill:" + C_WHITE + ";-fx-font-size:13;");

                    Button delBtn = new Button("Delete");
                    delBtn.setStyle("-fx-background-color:#F85149;-fx-text-fill:white;-fx-font-size:11;"
                            + "-fx-padding:5 12;-fx-background-radius:6;-fx-cursor:hand;");
                    delBtn.setOnAction(e -> {
                        try {
                            deleteVehicle(v.vehicleId, user.userId);
                            user.vehicles.remove(v);
                            inform("Vehicle deleted.");
                            stage.close();
                            showVehiclesDialog();
                        } catch (Exception ex) { alert("Error: " + ex.getMessage()); }
                    });

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    row.getChildren().addAll(info, spacer, delBtn);
                    content.getChildren().add(row);
                }
            }

            // Add-vehicle form
            content.getChildren().add(mkHeading("Add New Vehicle"));
            TextField    nameField = mkField("Vehicle Name (e.g. Honda Civic)"); nameField.setPrefWidth(160);
            ComboBox<String> fcBox = new ComboBox<>();
            fcBox.getItems().addAll("PETROL", "DIESEL", "LDO", "CNG");
            fcBox.setValue("PETROL"); fcBox.setStyle(S_COMBO);
            TextField milField = mkField("Mileage km/L"); milField.setPrefWidth(100);
            Button addBtn = new Button("Add"); addBtn.setStyle(S_BTN);

            addBtn.setOnAction(e -> {
                try {
                    double mil = Double.parseDouble(milField.getText().trim());
                    addVehicle(user.userId, nameField.getText().trim(), fcBox.getValue(), mil);
                    user.vehicles.clear();
                    user.vehicles.addAll(loadUserVehicles(user.userId));
                    inform("Vehicle added!");
                    stage.close();
                    showVehiclesDialog();
                } catch (Exception ex) { alert("Error: " + ex.getMessage()); }
            });

            HBox addRow = new HBox(10, nameField, fcBox, milField, addBtn);
            addRow.setAlignment(Pos.CENTER_LEFT);
            content.getChildren().add(addRow);
        };

        refresh.run();

        StackPane root = new StackPane(content);
        root.setStyle(S_PAGE);
        root.setPadding(new Insets(20));
        stage.setScene(new Scene(root, 640, 520));
        stage.show();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  RESULTS BUILDER
    // ════════════════════════════════════════════════════════════════════════
    @SuppressWarnings("unchecked")
    private void buildResultsUI(VBox container, CostComparison cmp, int pax, int carCap) {
        boolean isEstimated = cmp.route.routeId < 0;

        // ── Route summary card ────────────────────────────────────────────
        VBox routeCard = new VBox(8);
        routeCard.setStyle(S_CARD);
        routeCard.getChildren().add(mkHeading("Route: " + cmp.route.getDisplayLabel()));
        routeCard.getChildren().add(mkSubtitle(
                "Distance: " + cmp.route.distanceKm + " km  |  " +
                        "Est. Time: " + cmp.route.getDurationFormatted() + "  |  " +
                        "Tolls: PKR " + String.format("%.0f", cmp.route.tollFeePkr) + "  |  " +
                        "Passengers: " + pax
        ));
        if (isEstimated) {
            Label estNote = new Label("⚠  Estimated via GPS coordinates — no exact route in database.");
            estNote.setStyle("-fx-text-fill:#FFB74D;-fx-font-size:12;-fx-font-style:italic;");
            routeCard.getChildren().add(estNote);
        }

        // ── Adjust costs for passenger / car count ────────────────────────
        int cars = (int) Math.ceil((double) pax / carCap);
        List<CostComparison.Option> displayOptions = new ArrayList<>();

        for (CostComparison.Option opt : cmp.sortedByCost()) {
            if (opt.vehicle.type.equals("Own Car")) {
                final double totalCost     = opt.cost * cars;
                final String vehicleName   = opt.vehicle.name + (cars > 1 ? " (" + cars + " cars)" : "");
                final Vehicle scaledVehicle = new Vehicle(vehicleName, "Own Car") {
                    @Override public double calculateCost(Route r)    { return totalCost; }
                    @Override public String getCostBreakdown(Route r)  { return opt.vehicle.getCostBreakdown(r) + (cars > 1 ? "\nCars: " + cars : ""); }
                    @Override public int    getComfortRating()         { return opt.vehicle.getComfortRating(); }
                };
                displayOptions.add(new CostComparison.Option(scaledVehicle, totalCost, opt.durationMins));
            } else {
                // Public transport and ride-hail: multiply by pax
                displayOptions.add(new CostComparison.Option(opt.vehicle, opt.cost * pax, opt.durationMins));
            }
        }
        displayOptions.sort(Comparator.comparingDouble(o -> o.cost));

        // ── Feature 1 — Smart Recommendation badges ───────────────────────
        CostComparison.Option cheapest = displayOptions.get(0);
        CostComparison.Option fastest  = displayOptions.stream()
                .min(Comparator.comparingInt(o -> o.durationMins)).orElse(cheapest);

        double maxCost = displayOptions.stream().mapToDouble(o -> o.cost).max().orElse(1);
        double maxDur  = displayOptions.stream().mapToInt(o  -> o.durationMins).max().orElse(1);

        // Balanced score: 55% cost + 30% speed + 15% comfort
        CostComparison.Option balanced = displayOptions.stream()
                .min(Comparator.comparingDouble(o ->
                        (o.cost / maxCost) * 0.55
                                + ((double) o.durationMins / maxDur) * 0.30
                                + (1.0 - o.vehicle.getComfortRating() / 5.0) * 0.15
                )).orElse(cheapest);

        HBox badgeRow = new HBox(14);
        badgeRow.setAlignment(Pos.CENTER_LEFT);
        badgeRow.getChildren().addAll(
                makeBadge("💰  CHEAPEST",    cheapest, C_DARK_GREEN,
                        "PKR " + String.format("%,.0f", cheapest.cost)),
                makeBadge("⚡  FASTEST",     fastest,  "#0D47A1",
                        fastest.getDuration()),
                makeBadge("🏅  BEST OVERALL", balanced, "#E65100",
                        "PKR " + String.format("%,.0f", balanced.cost) + "  |  " + balanced.getDuration()
                                + "  |  " + balanced.vehicle.getComfortRating() + "/5 comfort")
        );

        // ── Results table ─────────────────────────────────────────────────
        TableView<CostComparison.Option> table = new TableView<>();
        table.setStyle("-fx-background-color:" + C_CARD + ";");
        table.setPrefHeight(Math.min(55 + displayOptions.size() * 36, 360));

        addCol(table, "Transport",    210, o -> o.vehicle.name);
        addCol(table, "Type",         130, o -> o.vehicle.type);
        addCol(table, "Total (PKR)",  140, o -> String.format("%,.2f", o.cost));
        addCol(table, "Per Person",   110, o -> o.vehicle.type.equals("Own Car")
                ? "Shared" : "PKR " + String.format("%,.0f", o.cost / pax));
        addCol(table, "Duration",      90, o -> o.getDuration());
        addCol(table, "Comfort",       70, o -> o.vehicle.getComfortRating() + "/5");

        // Highlight cheapest row in green
        table.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(CostComparison.Option item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setStyle("-fx-background-color:" + C_CARD + ";");
                else if (getIndex() == 0)  setStyle("-fx-background-color:" + C_DARK_GREEN + ";-fx-text-fill:white;");
                else setStyle("-fx-background-color:" + (getIndex() % 2 == 0 ? "#252525" : "#2A2A2A") + ";-fx-text-fill:white;");
            }
        });
        for (TableColumn<?, ?> col : table.getColumns())
            col.setStyle("-fx-background-color:#333;-fx-text-fill:white;-fx-font-weight:bold;");
        table.getItems().addAll(displayOptions);

        // ── Bar chart ─────────────────────────────────────────────────────
        CategoryAxis  barX = new CategoryAxis();
        NumberAxis    barY = new NumberAxis();
        BarChart<String, Number> barChart = new BarChart<>(barX, barY);
        barChart.setTitle("Cost Comparison" + (pax > 1 ? " (" + pax + " passengers)" : ""));
        barChart.setLegendVisible(false);
        barChart.setPrefHeight(300);
        barChart.setStyle("-fx-background-color:" + C_CARD + ";");
        XYChart.Series<String, Number> barSeries = new XYChart.Series<>();
        displayOptions.forEach(o -> barSeries.getData().add(new XYChart.Data<>(o.vehicle.name, o.cost)));
        barChart.getData().add(barSeries);

        // ── Pie chart — cheapest per transport category ───────────────────
        Map<String, Double> byType = new LinkedHashMap<>();
        displayOptions.forEach(o -> byType.merge(o.vehicle.type, o.cost, Math::min));
        ObservableList<PieChart.Data> pieData = FXCollections.observableArrayList();
        byType.forEach((k, v) -> pieData.add(new PieChart.Data(k + " (PKR " + String.format("%,.0f", v) + ")", v)));
        PieChart pie = new PieChart(pieData);
        pie.setTitle("Cheapest per Category");
        pie.setPrefHeight(300);
        pie.setStyle("-fx-background-color:" + C_CARD + ";");

        HBox charts = new HBox(16, barChart, pie);
        charts.setAlignment(Pos.CENTER);

        // ── Save / Copy ───────────────────────────────────────────────────
        VBox actionBox = new VBox(10);

        Button copyBtn = new Button("Copy Results to Clipboard");
        copyBtn.setStyle(S_BTN_O);
        copyBtn.setOnAction(ev -> {
            StringBuilder sb = new StringBuilder("SafarSmart — " + cmp.route.getDisplayLabel() + "\n\n");
            displayOptions.forEach(o -> sb.append(o.vehicle.name)
                    .append("  —  PKR ").append(String.format("%,.0f", o.cost))
                    .append("  (").append(o.getDuration()).append(")\n"));
            ClipboardContent cc = new ClipboardContent();
            cc.putString(sb.toString());
            Clipboard.getSystemClipboard().setContent(cc);
            inform("Results copied to clipboard!");
        });

        if (currentUser.canSaveTrips()) {
            if (isEstimated) {
                actionBox.getChildren().addAll(mkSmall("* Estimated routes cannot be saved. Only exact DB routes are saved."), copyBtn);
            } else {
                TextField titleField = mkField("Enter a trip title (optional)");
                Button saveBtn = new Button("Save Trip"); saveBtn.setStyle(S_BTN);
                saveBtn.setOnAction(ev -> {
                    try {
                        RegisteredUser ru = (RegisteredUser) currentUser;
                        String title = titleField.getText().trim();
                        if (title.isEmpty()) title = cmp.route.origin.cityName + " to " + cmp.route.destination.cityName;

                        saveTrip(ru.userId, cmp.route.routeId, title,
                                "Best: " + cheapest.vehicle.name + " for " + pax + " pax");

                        double ownMin = displayOptions.stream()
                                .filter(o -> o.vehicle.type.equals("Own Car")).mapToDouble(o -> o.cost).min().orElse(0);
                        double rhMin  = displayOptions.stream()
                                .filter(o -> o.vehicle.type.equals("Ride-Hail")).mapToDouble(o -> o.cost).min().orElse(0);
                        double ptMin  = displayOptions.stream()
                                .filter(o -> o.vehicle.type.equals("Public Transport")).mapToDouble(o -> o.cost).min().orElse(0);

                        saveComparison(ru.userId, cmp.route.routeId, ownMin, rhMin, ptMin, cheapest.vehicle.name);
                        inform("Trip saved successfully!");
                    } catch (Exception ex) { alert("Could not save trip: " + ex.getMessage()); }
                });
                actionBox.getChildren().addAll(titleField, new HBox(12, saveBtn, copyBtn) {{ setAlignment(Pos.CENTER_LEFT); }});
            }
        } else {
            actionBox.getChildren().add(copyBtn);
        }

        // ── Feature 2 — Trip Planner suggestions ─────────────────────────
        VBox sugBox = new VBox(10);
        sugBox.setStyle(S_CARD);
        sugBox.getChildren().add(mkHeading("🗺  Pakistan Trip Planner — Suggestions Along Your Route"));

        try {
            List<Suggestion> originSug = getSuggestions(cmp.route.origin.cityId);
            List<Suggestion> destSug   = getSuggestions(cmp.route.destination.cityId);

            if (!originSug.isEmpty()) {
                Label cityLbl = new Label("  " + cmp.route.origin.cityName + ":");
                cityLbl.setStyle("-fx-text-fill:" + C_LIGHT_GREEN + ";-fx-font-size:14;-fx-font-weight:bold;");
                sugBox.getChildren().add(cityLbl);
                for (Suggestion s : originSug) {
                    Label l = new Label("    [" + s.type.toUpperCase() + "]  " + s.name
                            + (s.description != null ? "  —  " + s.description : ""));
                    l.setStyle("-fx-text-fill:" + C_WHITE + ";-fx-font-size:12;");
                    l.setWrapText(true);
                    sugBox.getChildren().add(l);
                }
            }

            if (!destSug.isEmpty()) {
                Label cityLbl = new Label("  " + cmp.route.destination.cityName + ":");
                cityLbl.setStyle("-fx-text-fill:" + C_LIGHT_GREEN + ";-fx-font-size:14;-fx-font-weight:bold;");
                sugBox.getChildren().add(cityLbl);
                for (Suggestion s : destSug) {
                    Label l = new Label("    [" + s.type.toUpperCase() + "]  " + s.name
                            + (s.description != null ? "  —  " + s.description : ""));
                    l.setStyle("-fx-text-fill:" + C_WHITE + ";-fx-font-size:12;");
                    l.setWrapText(true);
                    sugBox.getChildren().add(l);
                }
            }

            if (originSug.isEmpty() && destSug.isEmpty())
                sugBox.getChildren().add(mkSubtitle("No suggestions available for this route yet."));

        } catch (Exception ex) {
            sugBox.getChildren().add(mkSubtitle("Could not load suggestions: " + ex.getMessage()));
        }

        container.getChildren().addAll(routeCard, badgeRow, table, charts, actionBox, sugBox);
    }

    // ── Badge helper for Feature 1 ─────────────────────────────────────────
    private VBox makeBadge(String label, CostComparison.Option opt, String bgColor, String detail) {
        VBox badge = new VBox(4);
        badge.setStyle("-fx-background-color:" + bgColor + ";-fx-background-radius:10;-fx-padding:14;");
        badge.setPrefWidth(290);

        Label tag  = new Label(label);
        tag.setStyle("-fx-text-fill:" + C_LIGHT_GREEN + ";-fx-font-size:10;-fx-font-weight:bold;");
        Label name = new Label(opt.vehicle.name);
        name.setStyle("-fx-text-fill:white;-fx-font-size:15;-fx-font-weight:bold;");
        name.setWrapText(true);
        Label det  = new Label(detail);
        det.setStyle("-fx-text-fill:" + C_GREY + ";-fx-font-size:11;");

        badge.getChildren().addAll(tag, name, det);
        return badge;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  APPLICATION ENTRY POINT
    // ════════════════════════════════════════════════════════════════════════
    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        stage.setTitle("SafarSmart — Pakistan Travel Intelligence Platform");
        stage.setMaximized(true);
        try { getConnection(); } catch (SQLException e) { alert("Database connection error: " + e.getMessage()); }
        stage.setScene(buildLoginScene());
        stage.show();
    }

    @Override
    public void stop() {
        if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
    }

    public static void main(String[] args) { launch(args); }
}
