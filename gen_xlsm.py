from openpyxl import Workbook
wb = Workbook()
ws = wb.active
ws.append(["month", "region", "amount"])
ws.append([1, "华东", 1000])
ws.append([2, "华南", 2000])
ws.append([3, "海外", 3000])
ws.append([4, "华东", 1500])
wb.save("E:/springboot/test_upload.xlsm")
print("written test_upload.xlsm")
