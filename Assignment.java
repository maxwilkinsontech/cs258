import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.text.ParseException;

class Assignment {

	/***************************************************************************
	* Methods provided with coursework to take user input and connect to the
	* database.
	***************************************************************************/

	private static String readEntry(String prompt) {
		try {
			StringBuffer buffer = new StringBuffer();
			System.out.print(prompt);
			System.out.flush();
			int c = System.in.read();
			while(c != '\n' && c != -1) {
				buffer.append((char)c);
				c = System.in.read();
			}
			return buffer.toString().trim();
		} catch (IOException e) {
			return "";
		}
 	}

	public static Connection getConnection() {
		// User and password should be left blank. Do not alter!
		String user = "";
    	String passwrd = "";
    	Connection conn;

        try {
            Class.forName("oracle.jdbc.driver.OracleDriver");
        } catch (ClassNotFoundException x) {
            System.out.println("Driver could not be loaded");
        }

        try {
            conn = DriverManager.getConnection("jdbc:oracle:thin:@arryn-ora-prod-db-1.warwick.ac.uk:1521:cs2db",user,passwrd);
            return conn;
        } catch(SQLException e) {
            System.out.println("Error retrieving connection");
            return null;
        }
	}

	/***************************************************************************
	* Methods implemented by the options methods defined in the coursework.
	***************************************************************************/

	/**
	* @param conn An open database connection.
	* @param orderDate The date the order was made in string format 'DD-Mon-YY'.
	* @param orderType The type of order being placed [InStore, Collection, Delivery].
	* @return The ID of the order.
	*
	* This method insert a row into the ORDER table. It is used by options 1-3.
	*/
	private static int createOrder(Connection conn, String orderDate, String orderType) throws SQLException {
		String createOrdersql = "INSERT INTO ORDERS VALUES (?, ?, ?, ?)";
		int orderCompleted = orderType.equals("InStore") ? 1 : 0;

		String[] returnCols = {"OrderID"};
		PreparedStatement createOrder = conn.prepareStatement(createOrdersql, returnCols);
		createOrder.setString(1, null); // NULL as pk auto-generated.
		createOrder.setString(2, orderType);
		createOrder.setInt(3, orderCompleted);
		createOrder.setString(4, orderDate);
		int affectedRows = createOrder.executeUpdate();

		return returnGeneratedKey(affectedRows, createOrder);
	}

	/**
	* @param affectedRows Number of rows affected by the query.
	* @param statement PreparedStatement used to run the query.
	* @return The ID of the order.
	*
	* This method returns the auto-generated primary key of the created order.
	*/
	private static int returnGeneratedKey(int affectedRows, PreparedStatement statement) throws SQLException {
		int orderID;

		if (affectedRows == 0) {
			throw new SQLException("ERROR: Failed to create new Order");
		}

		ResultSet generatedKeys = statement.getGeneratedKeys();
		if (generatedKeys.next()) {
			orderID = generatedKeys.getInt(1);
		} else {
			throw new SQLException("ERROR: Failed to create new Order");
		}

		return orderID;
	}

	/**
	* @param conn An open database connection
	* @param productIDs An array of productIDs associated with an order
	* @param quantities An array of quantities of a product. The index of a quantity correspeonds with an index in productIDs
	* @param orderID The ID of the order the products are associated with.
	*
	* Before adding a product to the order, I check that there is
	* enough stock to fullfil the order, if there is not, the product is skipped
	* from being added to the order and the user is notified of this. If there
	* is enough stock of the product to complete the order then the number in
	* stock is reduced by the amount ordered.
	*/
	private static void addProducts(Connection conn, int[] productIDs, int[] quantities,
									int orderID) throws SQLException {
		String addProductSQL = "INSERT INTO ORDER_PRODUCTS VALUES (?, ?, ?)";
		PreparedStatement addProduct;
		int productID, quantity;
		int productsInOrder = 0;
		Integer StockLeft;

		for (int i=0; i<productIDs.length; i++) {
			productID = productIDs[i];
			quantity = quantities[i];
			// check that there is enough stock of the given product in inventory
			StockLeft = checkStock(conn, productID, quantity);
			// productID not valid or not enough stock
			if (StockLeft == null) {
				System.out.println("ERROR: There is no product with ID: " + productID);
				continue;
			} else if (StockLeft < 0) {
				System.out.println("Error: Insufficient stock for product with ID: " + productID);
				continue;
			}
			addProduct = conn.prepareStatement(addProductSQL);
			addProduct.setInt(1, orderID);
			addProduct.setInt(2, productID);
			addProduct.setInt(3, quantity);
			addProduct.executeUpdate();
			reduceStock(conn, productID, quantity);
			// Print the required text console.
			System.out.println("Product ID " + productID + " stock is now at " + StockLeft);
			productsInOrder++;
		}
		// Invalid productIDs are not added to the order. This means that there
		// could be an order without any products in it. If this is the case,
		// delete the order and notify the user.
		if (productsInOrder == 0) {
			deleteOrder(conn, orderID, "Deleted Order " + orderID + " due to lack of products.");
		}
	}

	/**
	* @param conn An open database connection.
	* @param productID Product ID of product being checked.
    * @param quantity Quantity of the product being ordered.
	* @return number of stock left if this order was fulfilled.
	*
	* This method checks that there are enough products in stock to fulfil the
	* order. The method returns number of stock left if this order was fulfilled.
	* This number can be negative and this fact is used to check if this product
	* should be added to the order.
	*/
	private static Integer checkStock(Connection conn, int productID, int quantity) throws SQLException {
		String query = ""
		+ "SELECT ProductStockAmount "
		+ "FROM INVENTORY "
		+ "WHERE ProductID = ?";

		PreparedStatement checkStock = conn.prepareStatement(query);
		checkStock.setInt(1, productID);
		ResultSet rs = checkStock.executeQuery();
		int productStock = 0;
		if (rs.next()) {
			productStock = rs.getInt(1);
		} else {
			return null;
		}

		return productStock - quantity;
	}

	/**
	* @param conn An open database connection.
	* @param productID Product ID of product being checked.
    * @param quantity Quantity of the product being ordered.
	*
	* This method reduces the ProductQuantity in the INVENTORY table for a given
	* product. This will never change the quantity to less than 0 due to the check
	* before calling this method.
	*/
	private static void reduceStock(Connection conn, int productID, int quantity) throws SQLException {
		String reduceStockSQL = "UPDATE INVENTORY SET ProductStockAmount = ProductStockAmount - ? WHERE ProductID = ?";
		PreparedStatement reduceStock = conn.prepareStatement(reduceStockSQL);
		reduceStock.setInt(1, quantity);
		reduceStock.setInt(2, productID);
		reduceStock.executeUpdate();
	}

	/**
	* @param conn An open database connection
	* @param orderID The ID of the order which the products are associated with.
	*
	* This method deletes an order with the given orderID.
	*/
	private static void deleteOrder(Connection conn, int orderID, String errorMessage)
									throws SQLException {
		String deleteOrderSQL = "DELETE FROM ORDERS WHERE OrderID = ?";
		PreparedStatement deleteOrder = conn.prepareStatement(deleteOrderSQL);
		deleteOrder.setInt(1, orderID);
		deleteOrder.executeUpdate();
		throw new SQLException(errorMessage);
	}

	/**
	* @param conn An open database connection
	* @param staffID The ID of the staff member who completed the order.
	* @param orderID The ID of the order which the products are associated with.
	*
	* This method creates a row in the STAFF_ORDERS table after verifyign the
	* validity of the staffID in the STAFF table.
	*/
	private static void addStaffOrder(Connection conn, int staffID, int orderID) throws SQLException {
		if (!validateStaffID(conn, staffID)) {
			System.out.println("Error: No staff found with ID: " + staffID);
			deleteOrder(conn, orderID, "Deleted Order " + orderID + " due to invalid staff ID associated with order.");
		}

		String staffOrderSQL = "INSERT INTO STAFF_ORDERS VALUES (?, ?)";
		PreparedStatement staffOrder = conn.prepareStatement(staffOrderSQL);
		staffOrder.setInt(1, staffID);
		staffOrder.setInt(2, orderID);
		staffOrder.executeUpdate();
	}


	/**
	* @param conn An open database connection
	* @param staffID The ID of the staff member who completed the order.
	*
	* This method validates the given staffID against the STAFF table.
	*/
	private static boolean validateStaffID(Connection conn, int staffID) throws SQLException {
		String verifyStaffIDSQL = "SELECT StaffID FROM STAFF WHERE StaffID = ?";
		PreparedStatement verifyStaffID = conn.prepareStatement(verifyStaffIDSQL);
		verifyStaffID.setInt(1, staffID);
		ResultSet rs = verifyStaffID.executeQuery();

		if (rs.next()) {
			return true;
		}
		return false;
	}

	/**
	* @param conn    An open database connection
	* @param orderID The ID of the order which the products are associated with.
	* @param fName The first name of the customer who will collect the order
	* @param LName The last name of the customer who will collect the order
	* @param collectionDate A string in the form of 'DD-Mon-YY' that represents the date the order will be collected
	*
	* This method create a row in the Collections table.
	*/
	private static void addCollection(Connection conn, int orderID, String FName, String LName,
									  String collectionDate) throws SQLException {
		String addCollectionSQL = "INSERT INTO COLLECTIONS VALUES (?, ?, ?, ?)";
		PreparedStatement addCollection = conn.prepareStatement(addCollectionSQL);
		addCollection.setInt(1, orderID);
		addCollection.setString(2, FName);
		addCollection.setString(3, LName);
		addCollection.setString(4, collectionDate);
		addCollection.executeUpdate();
	}

	/**
	* @param conn An open database connection
	* @param fName The first name of the customer who will receive the order
	* @param LName The last name of the customer who will receive the order
	* @param house The house name or number of the delivery address
	* @param street The street name of the delivery address
	* @param city The city name of the delivery address
	* @param deliveryDate A string in the form of 'DD-Mon-YY' that represents the date the order will be delivered
	*
	* This method creates a row in the Deliveries table.
	*/
	private static void addDelivery(Connection conn, int orderID, String FName, String LName,
									String house, String street, String city, String collectionDate)
									throws SQLException {
		String addDeliverySQL = "INSERT INTO DELIVERIES VALUES (?, ?, ?, ?, ?, ?, ?)";
		PreparedStatement addDelivery = conn.prepareStatement(addDeliverySQL);
		addDelivery.setInt(1, orderID);
		addDelivery.setString(2, FName);
		addDelivery.setString(3, LName);
		addDelivery.setString(4, house);
		addDelivery.setString(5, street);
		addDelivery.setString(6, city);
		addDelivery.setString(7, collectionDate);
		addDelivery.executeUpdate();
	}

	/***************************************************************************
	* Required method to be completed for the coursework.
	***************************************************************************/

	/**
	* @param conn An open database connection
	* @param productIDs An array of productIDs associated with an order
    * @param quantities An array of quantities of a product. The index of a quantity correspeonds with an index in productIDs
	* @param orderDate A string in the form of 'DD-Mon-YY' that represents thSystem.out.println("EmployeeName " + "TotalValueSold");
	* @param staffID The id of the staff member who sold the order
	*/
	public static void option1(Connection conn, int[] productIDs, int[] quantities, String orderDate, int staffID) {
		try {
			int orderID = createOrder(conn, orderDate, "InStore");
			addProducts(conn, productIDs, quantities, orderID);
			addStaffOrder(conn, staffID, orderID);
		} catch (SQLException e) {
	        System.out.println(e.getMessage());
	    }
	}

	/**
	* @param conn An open database connection
	* @param productIDs An array of productIDs associated with an order
    * @param quantities An array of quantities of a product. The index of a quantity correspeonds with an index in productIDs
	* @param orderDate A string in the form of 'DD-Mon-YY' that represents the date the order was made
	* @param collectionDate A string in the form of 'DD-Mon-YY' that represents the date the order will be collected
	* @param fName The first name of the customer who will collect the order
	* @param LName The last name of the customer who will collect the order
	* @param staffID The id of the staff member who sold the order
	*/
	public static void option2(Connection conn, int[] productIDs, int[] quantities, String orderDate, String collectionDate, String fName, String LName, int staffID) {
		try {
			int orderID = createOrder(conn, orderDate, "Collection");
			addProducts(conn, productIDs, quantities, orderID);
			addStaffOrder(conn, staffID, orderID);
			addCollection(conn, orderID, fName, LName, collectionDate);
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
	}

	/**
	* @param conn An open database connection
	* @param productIDs An array of productIDs associated with an order
    * @param quantities An array of quantities of a product. The index of a quantity correspeonds with an index in productIDs
	* @param orderDate A string in the form of 'DD-Mon-YY' that represents the date the order was made
	* @param deliveryDate A string in the form of 'DD-Mon-YY' that represents the date the order will be delivered
	* @param fName The first name of the customer who will receive the order
	* @param LName The last name of the customer who will receive the order
	* @param house The house name or number of the delivery address
	* @param street The street name of the delivery address
	* @param city The city name of the delivery address
	* @param staffID The id of the staff member who sold the order
	*/
	public static void option3(Connection conn, int[] productIDs, int[] quantities, String orderDate, String deliveryDate,
							   String fName, String LName, String house, String street, String city, int staffID) {
		try {
			int orderID = createOrder(conn, orderDate, "Delivery");
			addProducts(conn, productIDs, quantities, orderID);
			addStaffOrder(conn, staffID, orderID);
			addDelivery(conn, orderID, fName, LName, house, street, city, deliveryDate);
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
	}

	/**
	* @param conn An open database connection
	*/
	public static void option4(Connection conn) {
		String query = ""
		+ "SELECT a.ProductId, "
		+ "       ProductDesc, "
		+ "       ProductPrice * TotalSales AS TotalValueSold "
		+ "FROM INVENTORY a "
		+ "INNER JOIN "
		+ "    (SELECT ProductID, "
		+ "            SUM(ProductQuantity) AS TotalSales "
		+ "    FROM ORDER_PRODUCTS "
		+ "    GROUP BY ProductId) b "
		+ "ON a.ProductId = b.ProductID "
		+ "ORDER BY TotalValueSold DESC NULLS LAST";

		try {
			PreparedStatement statement = conn.prepareStatement(query);
			ResultSet rs = statement.executeQuery(query);

			System.out.format("%-10s%-31s%-14s\n", "ProductID ", "ProductDesc ", "TotalValueSold");
			while (rs.next()) {
				System.out.format("%-10d%-31s%-14s\n", rs.getInt(1), rs.getString(2), "£" + rs.getFloat(3));
			}
			statement.close();
		} catch (SQLException e) {
	        System.out.println(e.getMessage());
	    }
	}

	/**
	* @param conn An open database connection
	* @param date The target date to test collection deliveries against
	*/
	public static void option5(Connection conn, String date) {
		String findOrdersSQL = ""
		+ "SELECT ORDERS.OrderID "
		+ "FROM ORDERS "
		+ "INNER JOIN COLLECTIONS "
		+ "ON ORDERS.OrderID = COLLECTIONS.OrderID "
		+ "WHERE ORDERS.OrderType = 'Collection' AND ORDERS.OrderCompleted = 0 AND COLLECTIONS.CollectionDate < to_date(?, 'DD-MON-YY') - 7";
		String deleteOrdersSQL = ""
		+ "DELETE FROM ORDERS "
		+ "WHERE OrderID IN "
		+ "    (SELECT ORDERS.OrderID "
		+ "    FROM ORDERS "
		+ "    INNER JOIN COLLECTIONS "
		+ "    ON ORDERS.OrderID = COLLECTIONS.OrderID "
		+ "    WHERE ORDERS.OrderType = 'Collection' AND ORDERS.OrderCompleted = 0 AND COLLECTIONS.CollectionDate < to_date(?, 'DD-MON-YY') - 7)";

		try {
			PreparedStatement findOrders = conn.prepareStatement(findOrdersSQL);
			findOrders.setString(1, date);
			ResultSet rs = findOrders.executeQuery();
			PreparedStatement deleteOrders = conn.prepareStatement(deleteOrdersSQL);
			deleteOrders.setString(1, date);
			deleteOrders.executeUpdate();
			while (rs.next()) {
				System.out.println("Order " + rs.getInt(1) + " has been cancelled");
			}
		} catch (SQLException e) {
	        System.out.println(e.getMessage());
	    }
	}

	/**
	* @param conn An open database connection
	*/
	public static void option6(Connection conn) {
		String query = ""
		+ "SELECT FName, LName, TotalValueSold "
		+ "FROM STAFF a "
		+ "RIGHT JOIN "
		+ "    (SELECT StaffID, SUM(ProductQuantity * ProductPrice) AS TotalValueSold "
		+ "    FROM INVENTORY c "
		+ "    INNER JOIN "
		+ "        (SELECT ProductID, ProductQuantity, StaffID "
		+ "        FROM ORDER_PRODUCTS e "
		+ "        INNER JOIN "
		+ "            (SELECT OrderID, StaffID "
		+ "            FROM STAFF_ORDERS) f "
		+ "        ON e.OrderID = f.OrderID) d "
		+ "    ON c.ProductID = d.ProductID "
		+ "    GROUP BY StaffID) b "
		+ "ON a.StaffID = b.StaffID "
		+ "WHERE TotalValueSold >= 50000 "
		+ "ORDER BY TotalValueSold DESC";

		try {
			PreparedStatement statement = conn.prepareStatement(query);
			ResultSet rs = statement.executeQuery();

			System.out.format("\n%-30s%-14s\n", "EmployeeName ", "TotalValueSold");
			while (rs.next()) {
				System.out.format("%-30s%-14s\n", rs.getString(1) + " " + rs.getString(2)
								  , "£" + rs.getFloat(3));
			}
		} catch (SQLException e) {
	        System.out.println(e.getMessage());
	    }
	}

	/**
	* @param conn An open database connection
	*/
	public static void option7(Connection conn) {
		String getProductIDsSQL = ""
		+ "SELECT LISTAGG('''' || ProductID || '''', ',') "
		+ "WITHIN GROUP(ORDER BY ProductID) "
		+ "FROM (SELECT DISTINCT ProductID FROM SALES_TABLE)";
		String cols;
		try {
			PreparedStatement getProductIDs = conn.prepareStatement(getProductIDsSQL);
			ResultSet ids = getProductIDs.executeQuery();
			if (ids.next()) {
				cols = ids.getString(1);
			} else {
				throw new SQLException("ERROR: failed to retrive product IDs");
			}

			String createPivotTableSQL = ""
			+ "SELECT * "
			+ "FROM SALES_TABLE "
			+ "PIVOT (SUM(TotalValueSold) FOR ProductID IN (" + cols + "))";
			PreparedStatement createPivotTable = conn.prepareStatement(createPivotTableSQL);
			ResultSet table = createPivotTable.executeQuery();
			// print results in table form
			ResultSetMetaData tablemd = table.getMetaData();
			int columnCount = tablemd.getColumnCount();

			System.out.format("\n%-30s", "EmployeeName ");
			for (int i = 3; i <= columnCount; i++) {
				if (i == columnCount) {
					System.out.format("%-14s\n", "Product " + tablemd.getColumnName(i).replace("'", ""));
				} else {
					System.out.format("%-14s", "Product " + tablemd.getColumnName(i).replace("'", ""));
				}
			}
			while (table.next()) {
				System.out.format("%-30s", table.getString(1) + " " + table.getString(2));
				for (int i = 3; i <= columnCount; i++) {
					if (i == columnCount) {
						System.out.format("%-14s\n", table.getInt(i));
					} else {
						System.out.format("%-14s", table.getInt(i));
					}
			    }
			}
		} catch (SQLException e) {
	        System.out.println(e.getMessage());
	    }
	}

	/**
	* @param conn An open database connection
	* @param year The target year we match employee and product sales against
	*/
	public static void option8(Connection conn, int year) {
		String createProductViewSQL = ""
		+ "CREATE OR REPLACE VIEW PRODUCTS_OVER_20K AS "
		+ "    (SELECT c.ProductID "
		+ "    FROM INVENTORY c "
		+ "    INNER JOIN "
		+ "        (SELECT a.ProductID, SUM(ProductQuantity) AS ProductQuantity "
		+ "        FROM ORDER_PRODUCTS a "
		+ "        INNER JOIN ORDERS b "
		+ "        ON a.OrderID = b.OrderID "
		+ "        WHERE extract(year from b.OrderPlaced) = " + year
		+ "        GROUP BY a.ProductID) d "
		+ "    ON c.ProductID = d.ProductID "
		+ "    WHERE ProductQuantity * ProductPrice > 20000)";
		String getStaffNamesSQL = ""
		+ "SELECT FName, LName "
		+ "FROM STAFF z "
		+ "INNER JOIN "
		+ "    (SELECT StaffID "
		+ "    FROM "
		+ "        (SELECT StaffID, COUNT(DISTINCT ProductID) AS NumUniqueProductsSold "
		+ "        FROM ORDER_PRODUCTS x "
		+ "        INNER JOIN "
		+ "            (SELECT OrderID, a.StaffID "
		+ "            FROM STAFF_ORDERS a "
		+ "            INNER JOIN "
		+ "                (SELECT StaffID "
		+ "                FROM "
		+ "                    (SELECT StaffID, SUM(ProductQuantity * ProductPrice) AS TotalValueSold "
		+ "                    FROM INVENTORY c "
		+ "                    INNER JOIN "
		+ "                        (SELECT ProductID, ProductQuantity, StaffID "
		+ "                        FROM ORDER_PRODUCTS e "
		+ "                        INNER JOIN "
		+ "                            (SELECT a.OrderID, StaffID "
		+ "                            FROM STAFF_ORDERS a "
		+ "                            INNER JOIN ORDERS b "
		+ "                            ON a.OrderID = b.OrderID "
		+ "                            WHERE extract(year from b.OrderPlaced) = ?) f "
		+ "                        ON e.OrderID = f.OrderID) d "
		+ "                    ON c.ProductID = d.ProductID "
		+ "                    GROUP BY StaffID) "
		+ "                WHERE TotalValueSold >= 30000) b "
		+ "            ON a.StaffID = b.StaffID) y "
		+ "        ON x.OrderID = y. OrderID "
		+ "        WHERE ProductID IN "
		+ "            (SELECT ProductID FROM PRODUCTS_OVER_20K) "
		+ "        GROUP BY StaffID) "
		+ "    WHERE NumUniqueProductsSold = (SELECT COUNT(DISTINCT ProductID) FROM PRODUCTS_OVER_20K)) w "
		+ "ON z.StaffID = w.StaffID";

		try {
			PreparedStatement createProductView = conn.prepareStatement(createProductViewSQL);
			createProductView.executeUpdate();
			PreparedStatement getStaffNames = conn.prepareStatement(getStaffNamesSQL);
			getStaffNames.setInt(1, year);
			ResultSet rs = getStaffNames.executeQuery();

			System.out.format("\n%-40s\n", "EmployeeName ");
			while (rs.next()) {
				System.out.format("%-40s\n", rs.getString(1) + " " + rs.getString(2));
			}
		} catch (SQLException e) {
	        System.out.println(e.getMessage());
	    }
	}

	/***************************************************************************
	* Methods and Classes for the inputting of the data.
	***************************************************************************/

	/**
	* colect the basic order info that is common to options 1-3
	*/
	private static Order collectOrderInfo(Connection conn) {
		ArrayList<Integer> productIDsList = new ArrayList<Integer>();
		ArrayList<Integer> quantitiesList = new ArrayList<Integer>();
		String orderDate, inputAnother;
		int productID, quantity;
		Integer stock;

		while (true) {
			productID = collectInteger("product ID");
			quantity = collectInteger("quantity sold");
			// validate the product
			try {
				stock = checkStock(conn, productID, quantity);
				if (stock == null || stock < 0) {
					System.out.println("ERROR: Invalid product ID or insufficient stock");
					continue;
				}
			} catch (SQLException e) {
		        System.out.println(e.getMessage());
				continue;
		    }

			productIDsList.add(productID);
			quantitiesList.add(quantity);

			inputAnother = readEntry("Is there another product in the order (Y/N): ");
			if (inputAnother.equals("N") || inputAnother.equals("n")) {
				break;
			}
		}

		orderDate = collectDate("Enter the date sold: ");

		int[] productIDs = new int[productIDsList.size()];
		int[] quantities = new int[quantitiesList.size()];
		for (int i=0; i<productIDsList.size(); i++) {
			productIDs[i] = productIDsList.get(i).intValue();
			quantities[i] = quantitiesList.get(i).intValue();
		}

		Order order = new Order(productIDs, quantities, orderDate);
		return order;
	}

	/**
	* collect and validate the date entered
	*/
	private static String collectDate(String consoleMessage) {
		boolean dateValid = false;
		String date;
		do {
			date = readEntry(consoleMessage);
			dateValid = validateDate(date);
		} while (!dateValid);
		return date;
	}

	/**
	* Validate that the date inputted is in the correct format for the
	* database. Format is dd-MM-yyyy e.g. 09-oct-19 for the date 09/10/19
	*/
	private static boolean validateDate(String date) {
		DateFormat df = new SimpleDateFormat("dd-MMM-yy");
		df.setLenient(false);
		try {
            df.parse(date);
            return true;
        } catch (ParseException e) {
			System.out.println("ERROR: dates must be in format dd-MMM-yy e.g. 09-Oct-19");
            return false;
        }
	}

	/**
	* Collect and validate the date of collection. This is also checked so
	* that it is after the orderDate.
	*/
	private static String collectFutureDate(String itemName, String orderDate) {
		DateFormat df = new SimpleDateFormat("dd-MMM-yy");
		String collectionDate;
		int dateComparison;

		while (true) {
			collectionDate = collectDate("Enter the date of " + itemName + ": ");

			try {
				dateComparison = df.parse(orderDate).compareTo(df.parse(collectionDate));
			} catch (ParseException e) {
				dateComparison = 1;
			}
			// inputted date is after given date => valid
			if (dateComparison <= 0) {
				break;
			}
			System.out.println("ERROR: " + itemName + " date must be after the order date.");
		}
		return collectionDate;
	}

	/**
	* collect and validate an int from the user. Must be greater than zero.
	*/
	private static int collectInteger(String itemName) {
		int inputInt;
		while (true) {
			try {
				inputInt = Integer.parseInt(readEntry("Enter a " + itemName + ": "));
				if (inputInt > 0) {
					break;
				}
				System.out.println("ERROR: " + itemName + " must be positive and greater than zero.");
			} catch (NumberFormatException e) {
				System.out.println("ERROR: " + itemName + " must be an integer.");
			}
		}
		return inputInt;
	}

	/**
	* Collect and validate a staffID.
	*/
	private static int collectStaffID(Connection conn) {
		int staffID;

		while (true) {
			staffID = collectInteger("staff ID");

			try {
				if (validateStaffID(conn, staffID)) {
					break;
				}
				System.out.println("ERROR: Invalid staff ID");
			} catch (SQLException e) {
				System.out.println(e.getMessage());
				continue;
			}
		}
		return staffID;
	}

	/**
	* Collect and validate a year from the user.
	*/
	private static int collectYear() {
		int year;
		while (true) {
			year = collectInteger("year");
			if (1000 <= year && year <= 9999) {
				break;
			}
			System.out.println("ERROR: please enter a valid year.");
		}
		return year;
	}

	/**
	* class to store the basic infomation to all orders
	*/
	private static class Order {
		int[] productIDs;
		int[] quantities;
		String orderDate;

		public Order(int[] productIDs, int[] quantities, String orderDate) {
			this.productIDs = productIDs;
			this.quantities = quantities;
			this.orderDate = orderDate;
		}
	}

	public static void main(String args[]) throws SQLException, IOException {
		// You should only need to fetch the connection details once
		Connection conn = getConnection();

		String prompt = "\n(1) In-Store Purchases\n"
					  + "(2) Collection\n"
					  + "(3) Delivery\n"
					  + "(4) Biggest Sellers\n"
					  + "(5) Reserved Stock\n"
					  + "(6) Staff Life-Time Sucess\n"
					  + "(7) Staff Contributions\n"
					  + "(8) Employees of the Year\n"
					  + "(0) Quit\n"
					  + "Enter your choice: ";
		String option, fName, LName, house, street, city, date;
		Order order;
		int staffID;

		scanner: while (true) {
			option = readEntry(prompt);
			switch(option) {
				case "1":
					order = collectOrderInfo(conn);
					staffID = collectStaffID(conn);
					option1(conn, order.productIDs, order.quantities, order.orderDate,
							staffID);
					break;
				case "2":
					order = collectOrderInfo(conn);
					date = collectFutureDate("collection", order.orderDate);
					fName = readEntry("Enter the first name of the collector: ");
					LName = readEntry("Enter the last name of the collector: ");
					staffID = collectStaffID(conn);
					option2(conn, order.productIDs, order.quantities, order.orderDate,
							date, fName, LName, staffID);
					break;
				case "3":
					order = collectOrderInfo(conn);
					date = collectFutureDate("delivery", order.orderDate);
					fName = readEntry("Enter the first name of the recipient: ");
					LName = readEntry("Enter the last name of the recipient: ");
					house = readEntry("Enter the house name/no: ");
					street = readEntry("Enter the street: ");
					city = readEntry("Enter the city: ");
					staffID = collectStaffID(conn);
					option3(conn, order.productIDs, order.quantities, order.orderDate,
							date, fName, LName, house, street, city, staffID);
					break;
				case "4":
					option4(conn);
					break;
				case "5":
					date = collectDate("Enter the date of delivery: ");
					option5(conn, date);
					break;
				case "6":
					option6(conn);
					break;
				case "7":
					option7(conn);
					break;
				case "8":
					option8(conn, collectYear());
					break;
				case "0":
					break scanner;
			}
		};

		conn.close();
	}
}
